package cordis.demo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cordis.agent.AgentPlugin;
import cordis.agent.AgentResult;
import cordis.agent.AgentService;
import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.SystemProvider;
import cordis.agent.Tool;
import cordis.agent.fs.FileSystemTools;
import cordis.agent.llm.OpenAiLlmClient;
import cordis.agent.skill.SkillTools;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.fs.FileSystemPlugin;
import cordis.skill.Skill;
import cordis.skill.SkillPlugin;
import cordis.skill.SkillService;
import cordis.skill.SkillSlice;
import cordis.util.Maps;

/**
 * skill 层演示：能力层（{@code cordis.skill}）的扫描/清单/窗口断言 +
 * 适配层（{@code cordis.agent.skill}）的「清单 → 自己挑一个 → 加载正文 →
 * 按正文指引再读资源文件」完整链路。
 *
 * <p>三个 section：
 *
 * <ol>
 *   <li><b>能力层断言</b>（离线）：skill.json （含 \n / \" 转义）解析、元数据缺失
 *       与写坏时的退化、陪跑目录跳过、清单截断、正文窗口与续读、越界与未知技能；</li>
 *   <li><b>脚本 LLM 全链路</b>（离线）：system 每轮都带技能清单（每轮求值），
 *       模型 use_skill 拿正文、再按正文指引 read_file 读二级资源；最后
 *       卸载 skills fiber，验证清单与工具一起消失（依赖级联）；</li>
 *   <li><b>真实模型</b>：一句"帮我写发布说明"，模型自己去 system 清单里
 *       认出 release-notes 技能并加载。</li>
 * </ol>
 *
 * <p>section 3 需要 {@code CORDIS_JAVA_APP_ID} 和 {@code CORDIS_JAVA_BASE_URL}（同
 * OpenAiAgentDemo），没设置就跳过；前两个 section 永远执行，可进回归。
 *
 * <p>skill 正文规模的真实数据（本机 48 个 SKILL.md 实测）：最小 1.7KB、
 * 中位 14.5KB、最大 97KB——所以正文必须按窗口续读，不能一次性全量回填。
 */
public final class SkillAgentDemo {

    private static final String DEFAULT_MODEL = "glm-5.3";

    /** section 2/3 共用的 system 基底：告诉模型清单在哪、该怎么用 */
    private static final String BASE_SYSTEM =
            "你可以调用工具。system 里如果有技能清单，遇到匹配的任务必须先 use_skill"
                    + " 加载该技能的正文，再严格按正文的指引操作；不得仅凭清单里的描述直接动手。"
                    + "回答用中文，简洁。";

    /** markdown-writer 的正文（元数据在 skill.json，正文里不再带 frontmatter） */
    private static final String MARKDOWN_SKILL =
            "# Markdown 报告规范\n"
                    + "动手前先读 references/format.md 确认段落模板。\n"
                    + "1. 摘要放在最前\n"
                    + "2. 风险段必须列出每条的影响面\n";

    /** 带转义（\n 与 \"）的元数据，用来验证 JSON 解码而不是敲行字符串 */
    private static final String MARKDOWN_META =
            "{\"description\":\"输出符合团队规范的 Markdown 报告，\\n"
                    + "需包含摘要与风险两段（\\\"三句以内\\\"）\"}";

    private static final String HUGE_META = "{\"description\":\"超长正文，用来验证窗口续读\"}";

    private static final String RELEASE_NOTES_META =
            "{\"description\":\"团队发布说明的格式与口径规范（本周变更对外发布时使用）\"}";

    private static final String FORMAT_REFERENCE =
            "模板: # 标题 / ## 摘要 / ## 风险（每条风险必须写影响面）\n";

    private SkillAgentDemo() {
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += sectionLayer();
        failures += sectionScriptedAgent();
        System.out.println();
        if (failures > 0) {
            System.out.println("离线断言失败 " + failures + " 个，跳过模型部分");
            System.exit(1);
        }

        String appId = env("CORDIS_JAVA_APP_ID", null);
        String baseUrl = env("CORDIS_JAVA_BASE_URL", null);
        if (appId == null || baseUrl == null) {
            System.out.println("跳过模型部分：缺少 CORDIS_JAVA_APP_ID / CORDIS_JAVA_BASE_URL"
                    + "（离线断言已全部通过）");
            return;
        }
        sectionModel(appId, baseUrl, env("CORDIS_JAVA_MODEL", DEFAULT_MODEL));
    }

    // ---------- 1. 能力层：扫描 / 清单 / 窗口 ----------

    private static int sectionLayer() throws Exception {
        System.out.println("== 1. 能力层：扫描、清单、正文窗口（离线断言） ==");
        Path root = Files.createTempDirectory("cordis-skills-");
        Context ctx = Context.root();
        int failures = 0;
        try {
            Path skillsDir = root.resolve("skills");
            seedSkills(skillsDir);
            // 窗口与描述上限故意压小：断言的是"按窗口续读"，不是读 30KB 的耐心
            Fiber skillsFiber = ctx.registry.use(SkillPlugin.INSTANCE,
                    Maps.of("root", skillsDir.toString(),
                            "maxLoadBytes", 1024,
                            "maxDescriptionChars", 30,
                            "createRoot", true),
                    null).await().join();
            try {
                final SkillService skills = ctx.get("skills");

                failures += ok("扫出 4 个技能且字典序、陪跑目录被跳过", () -> {
                    List<String> names = new ArrayList<>();
                    for (Skill skill : skills.list()) {
                        names.add(skill.name);
                    }
                    System.out.println("   清单: " + names + "（notes-only/ 无 SKILL.md，应缺席）");
                    return names.equals(Arrays.asList(
                            "bad-meta", "csv-reporter", "huge", "markdown-writer"));
                });
                failures += ok("skill.json 里的换行与引号转义正确解码", () -> {
                    Skill skill = skills.find("markdown-writer");
                    System.out.println("   description: "
                            + skill.description.replace("\n", "\\n"));
                    return skill.description.contains("Markdown 报告")
                            && skill.description.contains("摘要与风险")
                            && skill.description.contains("\n")           // \n 真的解成了换行
                            && skill.description.contains("\"三句以内\""); // \" 真的解成了引号
                });
                failures += ok("无 skill.json 时退化为正文首个非空行", () -> {
                    Skill skill = skills.find("csv-reporter");
                    System.out.println("   description: " + skill.description);
                    return skill.description.startsWith("# CSV");
                });
                failures += ok("skill.json 写坏时退化并继续加载（见 stderr 警告）", () -> {
                    Skill skill = skills.find("bad-meta");
                    System.out.println("   description: " + skill.description);
                    return skill != null && skill.description.startsWith("# 坏元数据技能");
                });
                failures += ok("清单只含名字与截断后的描述，不含正文", () -> {
                    String section = skills.systemSection();
                    System.out.println("   清单段 " + section.length() + " 字符:");
                    for (String line : section.split("\n")) {
                        System.out.println("     | " + line);
                    }
                    return section.contains("use_skill")
                            && section.contains("markdown-writer")
                            && section.contains("…")                 // 描述被截断
                            && !section.contains("动手前先读")        // 正文没混进来
                            && !section.contains("notes-only");
                });
                failures += ok("正文首窗正好 maxLoadBytes 且标为截断", () -> {
                    SkillSlice slice = skills.bodySync("huge", 0);
                    System.out.println("   " + slice);
                    return slice.offset == 0 && slice.length == 1024 && slice.truncated();
                });
                failures += ok("续读末尾窗口不带截断标记", () -> {
                    Skill skill = skills.find("huge");
                    SkillSlice slice = skills.bodySync("huge", (int) skill.totalBytes - 500);
                    System.out.println("   " + slice);
                    return slice.length == 500 && !slice.truncated();
                });
                failures += rejected("offset 越界被拒（提示 totalBytes）",
                        () -> skills.bodySync("huge", (int) skills.find("huge").totalBytes));
                failures += rejected("未知技能被拒并列出可用名字",
                        () -> skills.bodySync("no-such-skill", 0));
            } finally {
                skillsFiber.dispose();
                ctx.scheduler.awaitIdle();
                ctx.scheduler.shutdown();
            }
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        return failures;
    }

    // ---------- 2. 脚本 LLM：清单 -> use_skill -> 按指引读资源 ----------

    private static int sectionScriptedAgent() throws Exception {
        System.out.println("== 2. 适配层：清单 -> use_skill -> 按正文指引读资源（离线） ==");
        Path root = Files.createTempDirectory("cordis-skill-agent-");
        final Context ctx = Context.root();
        final ScriptedSkillLlm llm = new ScriptedSkillLlm();
        int failures = 0;
        List<Fiber> fibers = new ArrayList<>();
        final Disposable toolHook = ctx.on(AgentService.EVT_TOOL, a -> {
            System.out.println("   [tool] " + a[0] + " -> " + brief(String.valueOf(a[1]), 70));
            return null;
        });
        try {
            Path skillsDir = root.resolve("skills");
            seedSkills(skillsDir);
            fibers.add(ctx.registry.use(FileSystemPlugin.INSTANCE,
                    Maps.of("root", root.toString()), null).await().join());
            Fiber skillsFiber = ctx.registry.use(SkillPlugin.INSTANCE,
                    Maps.of("root", skillsDir.toString()), null).await().join();
            fibers.add(skillsFiber);
            // system 每轮从 ctx 重新解析 skills：provider 捕获的是上下文而不是服务实例，
            // 所以 skills 重装（新实例）之后下一轮就能看到新清单
            SystemProvider system = () -> {
                SkillService current = ctx.get("skills");
                String section = current == null ? "" : current.systemSection();
                return section.isEmpty() ? BASE_SYSTEM : BASE_SYSTEM + "\n\n" + section;
            };
            fibers.add(ctx.registry.use(AgentPlugin.INSTANCE,
                    Maps.of("llm", llm, "maxSteps", 5, "system", system),
                    null).await().join());
            fibers.add(ctx.registry.use(FileSystemTools.INSTANCE,
                    Maps.of(), Maps.of("filesystem", true, "agent.tools", true))
                    .await().join());
            fibers.add(ctx.registry.use(SkillTools.INSTANCE,
                    Maps.of(), Maps.of("skills", true, "agent.tools", true))
                    .await().join());

            AgentService agent = ctx.get("agent");
            List<LlmMessage> history = new ArrayList<>();
            AgentResult result = agent.run(history,
                    "按技能规范，告诉我团队 Markdown 报告该有哪几段。").completion().join();

            System.out.println("   终答: " + result.answer);
            System.out.println("   终态: " + result.stopReason + "，" + result.steps + " 轮，"
                    + result.toolCalls + " 次工具");

            failures += ok("三轮走完：use_skill -> read_file -> 终答",
                    () -> result.stopReason == AgentResult.StopReason.ANSWERED
                            && result.steps == 3 && result.toolCalls == 2);
            failures += ok("system 每轮都带技能清单（共 3 轮）", () -> {
                if (llm.systems.size() != 3) {
                    return false;
                }
                for (String systemSeen : llm.systems) {
                    if (!systemSeen.contains("markdown-writer")
                            || !systemSeen.contains("csv-reporter")
                            || !systemSeen.startsWith(BASE_SYSTEM)) {
                        return false;
                    }
                }
                return true;
            });
            failures += ok("模型第一轮就看见 use_skill 与 read_file",
                    () -> llm.firstRoundTools.contains("use_skill")
                            && llm.firstRoundTools.contains("read_file"));
            failures += ok("use_skill 回填了技能正文（带头部与技能名）",
                    () -> history.get(2).content.startsWith("[skill: markdown-writer")
                            && history.get(2).content.contains("Markdown 报告规范"));
            failures += ok("read_file 回填了正文指引的资源内容",
                    () -> history.get(4).content.contains("影响面"));

            // 2b. 依赖级联：skills 卸载后，清单与 use_skill 一起消失
            skillsFiber.dispose();
            ctx.scheduler.awaitIdle();
            List<String> before = new ArrayList<>(llm.systems);
            List<LlmMessage> after = new ArrayList<>();
            llm.reset();
            agent.run(after, "还在吗？").completion().join();
            failures += ok("skills 卸载后 system 只剩基底、use_skill 不可见", () -> {
                String systemSeen = llm.systems.get(0);
                System.out.println("   卸载后 system 不含清单: "
                        + !systemSeen.contains("markdown-writer"));
                return !systemSeen.contains("markdown-writer")
                        && systemSeen.equals(BASE_SYSTEM)
                        && !llm.firstRoundTools.contains("use_skill");
            });
            System.out.println("   卸载前 system 轮数记录: " + before.size());
        } finally {
            toolHook.dispose();
            for (Fiber fiber : fibers) {
                fiber.dispose();
            }
            ctx.scheduler.awaitIdle();
            ctx.scheduler.shutdown();
            deleteRecursively(root);
        }
        System.out.println();
        return failures;
    }

    // ---------- 3. 真实模型：自己从清单里认出技能 ----------

    private static void sectionModel(String appId, String baseUrl, String model)
            throws Exception {
        System.out.println("== 3. 真实模型：自己认出 release-notes 技能并加载 ==");
        System.out.println("   model: " + model);
        watchdog(180);
        Path root = Files.createTempDirectory("cordis-skill-model-");
        final Context ctx = Context.root();
        final Disposable toolHook = ctx.on(AgentService.EVT_TOOL, a -> {
            System.out.println("   [tool] " + a[0] + " -> " + brief(String.valueOf(a[1]), 90));
            return null;
        });
        OpenAiLlmClient llm = new OpenAiLlmClient(baseUrl, appId, model, true, 0);
        List<Fiber> fibers = new ArrayList<>();
        try {
            Path skillsDir = root.resolve("skills");
            seedReleaseNotesSkill(skillsDir);
            Files.write(root.resolve("CHANGES.md"),
                    ("- 新增：计费规则支持按门店维度配置\n"
                            + "- 修复：重复提交导致的凭证重复生成\n"
                            + "- 风险：历史数据未回刷，报表口径可能不一致\n")
                            .getBytes(StandardCharsets.UTF_8));
            fibers.add(ctx.registry.use(FileSystemPlugin.INSTANCE,
                    Maps.of("root", root.toString(),
                            "approval", (cordis.fs.ApprovalGate) request -> true),
                    null).await().join());
            fibers.add(ctx.registry.use(SkillPlugin.INSTANCE,
                    Maps.of("root", skillsDir.toString()), null).await().join());
            SystemProvider system = () -> {
                SkillService current = ctx.get("skills");
                String section = current == null ? "" : current.systemSection();
                return section.isEmpty() ? BASE_SYSTEM : BASE_SYSTEM + "\n\n" + section;
            };
            fibers.add(ctx.registry.use(AgentPlugin.INSTANCE,
                    Maps.of("llm", llm, "maxSteps", 6, "system", system),
                    null).await().join());
            fibers.add(ctx.registry.use(FileSystemTools.INSTANCE,
                    Maps.of(), Maps.of("filesystem", true, "agent.tools", true))
                    .await().join());
            fibers.add(ctx.registry.use(SkillTools.INSTANCE,
                    Maps.of(), Maps.of("skills", true, "agent.tools", true))
                    .await().join());

            AgentService agent = ctx.get("agent");
            List<LlmMessage> history = new ArrayList<>();
            long start = System.currentTimeMillis();
            AgentResult result = agent.run(history,
                    "照我们团队的发布说明规范，把这周的变更写进回答里"
                            + "（直接在回答中给出，不要创建文件），素材在 CHANGES.md。")
                    .completion().join();
            long cost = System.currentTimeMillis() - start;

            boolean usedSkill = false;
            for (LlmMessage message : history) {
                if ("tool".equals(message.role)
                        && message.content.startsWith("[skill: release-notes")) {
                    usedSkill = true;
                }
            }
            System.out.println("   终答: " + result.answer);
            System.out.println("   终态: " + result.stopReason + "，" + result.steps + " 轮，"
                    + result.toolCalls + " 次工具，耗时 " + cost + "ms");
            System.out.println("   自己加载了 release-notes 技能: " + usedSkill);
            if (result.stopReason != AgentResult.StopReason.ANSWERED || !usedSkill) {
                System.out.println("   [FAIL] 模型没用技能就作答了");
                System.exit(1);
            }
        } finally {
            toolHook.dispose();
            for (Fiber fiber : fibers) {
                fiber.dispose();
            }
            ctx.scheduler.awaitIdle();
            ctx.scheduler.shutdown();
            llm.dispose();
            deleteRecursively(root);
        }
    }

    // ---------- 脚本模型 ----------

    /**
     * 按 history 末条决定下一步的脚本模型：user → use_skill；
     * 看到 skill 正文 → read_file 它指引的资源；其余 → 终答。
     *
     * <p>同时记录每轮收到的 system 与首轮工具集——这两样都是"框架是否真的
     * 把动态段下发给了模型"的证据，真实模型那边无法直接观察。
     */
    private static final class ScriptedSkillLlm implements LlmClient {

        /** 每轮收到的 system 指令 */
        final List<String> systems = new ArrayList<>();

        /** 首轮可见的工具名 */
        final List<String> firstRoundTools = new ArrayList<>();

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools, String systemSeen) {
            systems.add(systemSeen == null ? "" : systemSeen);
            if (firstRoundTools.isEmpty()) {
                for (Tool tool : tools) {
                    firstRoundTools.add(tool.name());
                }
                System.out.println("   [llm] 首轮可见工具: " + firstRoundTools);
            }
            LlmMessage last = history.get(history.size() - 1);
            if ("user".equals(last.role)) {
                if (!hasTool(tools, "use_skill")) {
                    return LlmReply.answer("没有可用技能，直接回答");
                }
                return LlmReply.toolCall("call_skill", "use_skill",
                        "{\"name\":\"markdown-writer\"}");
            }
            if ("tool".equals(last.role) && last.content.startsWith("[skill:")) {
                return LlmReply.toolCall("call_read", "read_file",
                        "{\"path\":\"skills/markdown-writer/references/format.md\"}");
            }
            return LlmReply.answer("已按 markdown-writer 技能指引确认模板");
        }

        void reset() {
            systems.clear();
            firstRoundTools.clear();
        }

        private static boolean hasTool(List<Tool> tools, String name) {
            for (Tool tool : tools) {
                if (tool.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }

    }

    // ---------- 夹具 ----------

    private static void seedSkills(Path skillsDir) throws Exception {
        Path markdown = skillsDir.resolve("markdown-writer");
        Files.createDirectories(markdown.resolve("references"));
        Files.write(markdown.resolve("SKILL.md"),
                MARKDOWN_SKILL.getBytes(StandardCharsets.UTF_8));
        Files.write(markdown.resolve("skill.json"),
                MARKDOWN_META.getBytes(StandardCharsets.UTF_8));
        Files.write(markdown.resolve("references/format.md"),
                FORMAT_REFERENCE.getBytes(StandardCharsets.UTF_8));

        // 无 skill.json：应退化到正文首个非空行
        Path csv = skillsDir.resolve("csv-reporter");
        Files.createDirectories(csv);
        Files.write(csv.resolve("SKILL.md"),
                "# CSV 报表输出规范\n先确认表头，再逐行输出。\n".getBytes(StandardCharsets.UTF_8));

        Path huge = skillsDir.resolve("huge");
        Files.createDirectories(huge);
        Files.write(huge.resolve("SKILL.md"),
                repeat('x', 30 * 1024).getBytes(StandardCharsets.UTF_8));
        Files.write(huge.resolve("skill.json"),
                HUGE_META.getBytes(StandardCharsets.UTF_8));

        // 元数据写坏：应打警告并退化，但不拖垮整个目录
        Path broken = skillsDir.resolve("bad-meta");
        Files.createDirectories(broken);
        Files.write(broken.resolve("SKILL.md"),
                "# 坏元数据技能\n正文照常可用。\n".getBytes(StandardCharsets.UTF_8));
        Files.write(broken.resolve("skill.json"),
                "{description: 缺了引号}".getBytes(StandardCharsets.UTF_8));

        // 陪跑目录：没有 SKILL.md，应被跳过
        Files.createDirectories(skillsDir.resolve("notes-only"));
    }

    private static void seedReleaseNotesSkill(Path skillsDir) throws Exception {
        Path dir = skillsDir.resolve("release-notes");
        Files.createDirectories(dir);
        Files.write(dir.resolve("skill.json"),
                RELEASE_NOTES_META.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("SKILL.md"),
                ("# 发布说明规范\n"
                        + "1. 先用 read_file 读 CHANGES.md，拿到本周全部条目；\n"
                        + "2. 严格按「新增 / 修复 / 风险」三段输出，每条一行；\n"
                        + "3. 不要编造素材里没有的条目。\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(char c, int n) {
        char[] array = new char[n];
        Arrays.fill(array, c);
        return new String(array);
    }

    // ---------- 断言 helper ----------

    /** 期望抛 IllegalArgumentException（能力层的拒绝路径）；返回失败数 */
    private static int rejected(String label, Throwing task) {
        try {
            task.run();
            System.out.println("   [FAIL] " + label + " —— 没有拒绝");
            return 1;
        } catch (IllegalArgumentException e) {
            System.out.println("   [PASS] " + label + " —— " + e.getMessage());
            return 0;
        } catch (Exception e) {
            System.out.println("   [FAIL] " + label + " —— 抛了别的异常: " + e);
            return 1;
        }
    }

    /** 期望执行成功且谓词成立；返回失败数 */
    private static int ok(String label, Check task) {
        try {
            if (task.test()) {
                System.out.println("   [PASS] " + label);
                return 0;
            }
            System.out.println("   [FAIL] " + label + " —— 执行成功但验证为 false");
            return 1;
        } catch (Exception e) {
            System.out.println("   [FAIL] " + label + " —— 异常: " + e);
            return 1;
        }
    }

    private interface Throwing {

        void run() throws Exception;

    }

    private interface Check {

        boolean test() throws Exception;

    }

    private static String brief(String text, int max) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            System.err.println("[demo] cleanup failed: " + path);
                        }
                    });
        } catch (Exception e) {
            System.err.println("[demo] cleanup failed: " + dir);
        }
    }

    /** 超时兜底：模型挂住时不让 JVM 永远不退 */
    private static void watchdog(final long seconds) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("[demo] watchdog fired after " + seconds + "s, exiting");
            Runtime.getRuntime().halt(2);
        }, "demo-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

}
