package cordis.demo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cordis.agent.AgentPlugin;
import cordis.agent.AgentResult;
import cordis.agent.AgentService;
import cordis.agent.LlmMessage;
import cordis.agent.fs.FileSystemTools;
import cordis.agent.llm.OpenAiLlmClient;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.fs.FileSystemPlugin;
import cordis.fs.FileSystemService;
import cordis.util.Maps;

/**
 * 文件系统层演示：能力层（{@code cordis.fs}）的安全断言 + 适配层
 * （{@code cordis.agent.fs}）接真实模型做「读 → 改 → 再读」。
 *
 * <p>三个 section：
 *
 * <ol>
 *   <li><b>安全断言</b>（离线，不需要网络）：路径逃逸、符号链接逃逸、超大文件、
 *       二进制文件、edit 多处命中、审批拒绝——全部被能力层挡下；</li>
 *   <li><b>审批关口</b>：WriteRequest 摊开动作 / 路径 / 大小 / 预览，deny 的
 *       路径写不进去，allow 的可以；</li>
 *   <li><b>真实 agent loop</b>：模型用 edit_file 修 notes.md 里的 TODO，
 *       再读回确认——「能改代码」这件事第一次闭环。</li>
 * </ol>
 *
 * <p>section 3 需要 {@code MT_AIGC_APP_ID}（同 OpenAiAgentDemo），没设置就
 * 跳过；前两个 section 永远执行，可进回归。
 */
public final class FsAgentDemo {

    private static final String DEFAULT_BASE_URL = "https://aigc.sankuai.com/v1/openai/native";

    private static final String DEFAULT_MODEL = "LongCat-Flash-Chat";

    private static final String SYSTEM_PROMPT =
            "你可以读写工作区里的文件。改文件前先读原文；用 edit_file 时 oldText 必须"
                    + "逐字符复制原文且只出现一次。回答用中文，简洁。";

    private static final String NOTES_CONTENT =
            "# 项目备忘\n\n- [ ] TODO: 接入计费规则\n- [ ] TODO: 补充回归脚本\n\n"
                    + "负责人：小明\n";

    private FsAgentDemo() {
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += sectionSafety();
        failures += sectionApproval();
        System.out.println();
        if (failures > 0) {
            System.out.println("离线断言失败 " + failures + " 个，跳过模型部分");
            System.exit(1);
        }

        String appId = env("MT_AIGC_APP_ID", null);
        if (appId == null) {
            System.out.println("跳过模型部分：缺少 MT_AIGC_APP_ID（离线断言已全部通过）");
            return;
        }
        sectionModel(appId, env("MT_AIGC_BASE_URL", DEFAULT_BASE_URL),
                env("MT_AIGC_MODEL", DEFAULT_MODEL));
    }

    // ---------- 1. 能力层安全断言 ----------

    private static int sectionSafety() throws Exception {
        System.out.println("== 1. 能力层：沙箱与上限（离线断言） ==");
        Path root = Files.createTempDirectory("cordis-fs-");
        Context ctx = Context.root();
        int failures = 0;
        try {
            seedWorkspace(root);
            // 读 / 写上限故意压小：断言的是"先量再动"，不是读 2MB 的耐心
            Fiber fsFiber = ctx.registry.use(FileSystemPlugin.INSTANCE,
                    Maps.of("root", root.toString(),
                            "maxReadBytes", 2048,
                            "maxWriteBytes", 2048,
                            "approval", (cordis.fs.ApprovalGate) request -> true),
                    null).await().join();
            try {
                FileSystemService fs = ctx.get("filesystem");

                failures += rejected("相对 ../ 逃逸被拒",
                        () -> fs.readSync("../escape.txt", 1, 30));
                failures += rejected("绝对路径 /etc/passwd 被拒",
                        () -> fs.readSync("/etc/passwd", 1, 30));
                failures += rejected("list /etc 被拒",
                        () -> fs.listSync("/etc"));
                failures += rejected("符号链接指向墙外被拒",
                        () -> fs.readSync("link-out.txt", 1, 30));
                failures += rejected("超出 maxReadBytes 的文件被拒",
                        () -> fs.readSync("big.txt", 1, 30));
                failures += rejected("超出 maxWriteBytes 的写入被拒",
                        () -> fs.writeSync("x.txt", repeat('x', 4096)));
                failures += rejected("二进制文件（含 NUL）被拒",
                        () -> fs.readSync("blob.bin", 1, 30));
                failures += rejected("edit 命中 0 处被拒",
                        () -> fs.editSync("notes.md", "不存在的文本", "y"));
                failures += rejected("edit 命中 2 处被拒",
                        () -> fs.editSync("notes.md", "TODO", "DONE"));
                failures += ok("edit 恰好 1 处成功且落盘",
                        () -> {
                            fs.editSync("notes.md", "TODO: 接入计费规则", "DONE: 接入计费规则");
                            String after = new String(Files.readAllBytes(root.resolve("notes.md")),
                                    StandardCharsets.UTF_8);
                            return after.contains("DONE: 接入计费规则")
                                    && !after.contains("TODO: 接入计费规则");
                        });
                failures += ok("grep 找得到且带行号",
                        () -> {
                            List<cordis.fs.GrepHit> hits = fs.grepSync("负责人：(.*)", null);
                            return hits.size() == 1 && hits.get(0).line == 6
                                    && hits.get(0).text.contains("小明");
                        });
                failures += ok("grep 跳过 .git 目录",
                        () -> fs.grepSync("SECRET", null).isEmpty());
            } finally {
                fsFiber.dispose();
                ctx.scheduler.awaitIdle();
                ctx.scheduler.shutdown();
            }
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        return failures;
    }

    // ---------- 2. 审批关口 ----------

    private static int sectionApproval() throws Exception {
        System.out.println("== 2. 审批关口：deny 的写不进去，allow 的可以 ==");
        Path root = Files.createTempDirectory("cordis-gate-");
        Context ctx = Context.root();
        int failures = 0;
        try {
            final List<String> asked = new ArrayList<>();
            Fiber fsFiber = ctx.registry.use(FileSystemPlugin.INSTANCE,
                    Maps.of("root", root.toString(),
                            "approval", (cordis.fs.ApprovalGate) request -> {
                                asked.add(request.action + " " + request.path
                                        + " (" + request.bytes + "B)");
                                System.out.println("   审批: " + request.action + " "
                                        + new File(request.path).getName() + "， "
                                        + request.bytes + " bytes， 预览: " + request.preview);
                                return !request.path.contains("forbidden");
                            }),
                    null).await().join();
            try {
                FileSystemService fs = ctx.get("filesystem");

                failures += rejected("deny 路径写入被拒",
                        () -> fs.writeSync("forbidden.txt", "不许写"));
                failures += ok("deny 之后文件真的不存在",
                        () -> !Files.exists(root.resolve("forbidden.txt")));
                failures += ok("allow 路径写入成功",
                        () -> fs.writeSync("allowed.txt", "可以写").created);
                failures += ok("两次写入都经过了关口",
                        () -> asked.size() == 2 && asked.get(0).startsWith("write")
                                && asked.get(1).startsWith("write"));
            } finally {
                fsFiber.dispose();
                ctx.scheduler.awaitIdle();
                ctx.scheduler.shutdown();
            }
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        return failures;
    }

    // ---------- 3. 真实模型：读 → 改 → 再读 ----------

    private static void sectionModel(String appId, String baseUrl, String model)
            throws Exception {
        System.out.println("== 3. 真实 agent loop：edit_file 修 TODO 再读回确认 ==");
        System.out.println("   model: " + model);
        watchdog(180);
        Path root = Files.createTempDirectory("cordis-agent-fs-");
        Context ctx = Context.root();
        final Disposable toolHook = ctx.on(AgentService.EVT_TOOL, a -> {
            System.out.println("   [tool] " + a[0] + " -> " + brief(String.valueOf(a[1]), 90));
            return null;
        });
        OpenAiLlmClient llm = new OpenAiLlmClient(baseUrl, appId, model,
                SYSTEM_PROMPT, true, 0);
        List<Fiber> fibers = new ArrayList<>();
        try {
            seedWorkspace(root);
            fibers.add(ctx.registry.use(FileSystemPlugin.INSTANCE,
                    Maps.of("root", root.toString(),
                            "approval", (cordis.fs.ApprovalGate) request -> {
                                System.out.println("   审批: " + request.action + " "
                                        + new File(request.path).getName() + "， 预览: "
                                        + request.preview);
                                return true;
                            }),
                    null).await().join());
            fibers.add(ctx.registry.use(AgentPlugin.INSTANCE,
                    Maps.of("llm", llm, "maxSteps", 6), null).await().join());
            fibers.add(ctx.registry.use(FileSystemTools.INSTANCE,
                    Maps.of(), Maps.of("filesystem", true, "agent.tools", true))
                    .await().join());

            AgentService agent = ctx.get("agent");
            List<LlmMessage> history = new ArrayList<>();
            long start = System.currentTimeMillis();
            AgentResult result = agent.run(history,
                    "把 notes.md 里的两条 TODO 改成 DONE（用 edit_file，一次改一条），"
                            + "改完后重新读一遍 notes.md 确认，最后告诉我改了哪两条。")
                    .completion().join();
            long cost = System.currentTimeMillis() - start;

            String after = new String(Files.readAllBytes(root.resolve("notes.md")),
                    StandardCharsets.UTF_8);
            System.out.println("   终答: " + result.answer);
            System.out.println("   终态: " + result.stopReason + "，" + result.steps + " 轮，"
                    + result.toolCalls + " 次工具，耗时 " + cost + "ms");
            System.out.println("   修改后的文件：");
            for (String line : after.split("\n")) {
                System.out.println("     | " + line);
            }
            long todos = after.split("TODO", -1).length - 1;
            long dones = after.split("DONE", -1).length - 1;
            System.out.println("   磁盘验证: TODO 剩 " + todos + " 处（应为 0）， DONE 有 "
                    + dones + " 处（应为 2）");
            if (result.stopReason != cordis.agent.AgentResult.StopReason.ANSWERED
                    || todos != 0 || dones != 2) {
                System.out.println("   [FAIL] 模型没有按预期完成修改");
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

    // ---------- 夹具 ----------

    private static void seedWorkspace(Path root) throws Exception {
        Files.write(root.resolve("notes.md"), NOTES_CONTENT.getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("big.txt"),
                repeat('x', 4096).getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("blob.bin"),
                new byte[] {'h', 'e', 'a', 'd', 0, 't', 'a', 'i', 'l'});
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve(".git/config"),
                "SECRET=do-not-index".getBytes(StandardCharsets.UTF_8));
        // 指向墙外的符号链接：词法上完全合法，只有真实路径会露馅
        // （目标选 /etc/hosts，一定存在，避免误测成悬空链接）
        Files.createSymbolicLink(root.resolve("link-out.txt"),
                java.nio.file.Paths.get("/etc/hosts"));
    }

    private static String repeat(char c, int n) {
        char[] array = new char[n];
        Arrays.fill(array, c);
        return new String(array);
    }

    /** 期望抛异常（IllegalArgument / IllegalState），且消息非空；返回失败数 */
    private static int rejected(String label, Throwing task) {
        try {
            task.run();
            System.out.println("   [FAIL] " + label + " —— 期望被拒绝，实际成功");
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.out.println("   [PASS] " + label + "（拒绝: " + brief(e.getMessage(), 60) + "）");
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

    /**
     * 兜底看门狗：流式客户端 readTimeout 是 0（长思考不能设读超时），
     * 真卡在网络上时 demo 不该永远不退出。
     */
    private static void watchdog(final long seconds) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
                System.out.println("   [watchdog] " + seconds + "s 未完成，强制退出");
                System.exit(2);
            } catch (InterruptedException ignored) {
                // 正常走完
            }
        }, "demo-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

}
