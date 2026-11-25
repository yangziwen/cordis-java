package cordis.demo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.Tool;
import cordis.app.AgentServer;

/**
 * {@code cordis.app.AgentServer} 的回归入口（正式实现在 app 包，本类只负责
 * 断言与测试夹具）。
 *
 * <pre>
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.demo.AgentServerDemo
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.demo.AgentServerDemo \
 *        --port 8791 --root /tmp/agent-demo-ws
 * </pre>
 *
 * <p>与正式版的差别只有三处，全部是「让断言可复现」所必需：
 *
 * <ul>
 *   <li>模型换成 {@link OfflineLlm}：不依赖凭证、不花钱、结果可预测；</li>
 *   <li>额外挂一个 {@link SleepTool}：给并发断言造一个可观测的等待点；</li>
 *   <li>工作区初始化钩子播种两个示例技能 + 一份素材，从零目录也能跑。</li>
 * </ul>
 *
 * <p>默认工作区在系统临时目录下，不污染仓库。
 */
public final class AgentServerDemo {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentServerDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(System.getProperty("java.io.tmpdir"), "cordis-agent-demo");
        int port = 0;                                          // 0 = 系统分配，避免端口冲突
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--root".equals(args[i]) && i + 1 < args.length) {
                root = Paths.get(args[++i]);
            }
        }

        AgentServer.Config config = new AgentServer.Config()
                .port(port)
                .root(root)
                .llm(new OfflineLlm())
                .approval(request -> true)
                .tool(new SleepTool())
                .initializer(AgentServerDemo::seedWorkspace);
        AgentServer server = new AgentServer(config).start();
        int failures;
        try {
            failures = runSelftest(server.port());
        } finally {
            server.close();
        }
        System.out.println(failures == 0 ? "[agent-server-demo] PASS"
                : "[agent-server-demo] FAIL " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * 启完服务后打自己五个端点，全部走真实 HTTP：{@code /health}、{@code /chat}
     * （应触发 list_dir）、流式 {@code /chat}、三会话并发 {@code /chat}、
     * {@code /reset}。
     */
    private static int runSelftest(int port) throws Exception {
        String base = "http://" + AgentServer.DEFAULT_HOST + ":" + port;
        int failures = 0;

        JsonNode health = call(base + "/health", "GET", null);
        boolean healthOk = "ok".equals(health.path("status").asText())
                && health.path("tools").size() >= 7        // 5 个 fs + use_skill + sleep
                && health.path("skills").asInt() >= 2
                && "custom".equals(health.path("write").asText())
                && "custom(OfflineLlm)".equals(health.path("model").asText());
        System.out.println("   " + (healthOk ? "[PASS]" : "[FAIL]") + " /health → "
                + health.path("tools").size() + " tools, "
                + health.path("skills").asInt() + " skills, "
                + health.path("write").asText());
        if (!healthOk) {
            failures++;
        }

        JsonNode chat = call(base + "/chat", "POST",
                "{\"session\":\"selftest\",\"message\":\"看一下工作区有哪些文件\"}");
        boolean usedListDir = false;
        for (JsonNode event : chat.path("events")) {
            if ("tool".equals(event.path("type").asText())
                    && "list_dir".equals(event.path("name").asText())) {
                usedListDir = true;
            }
        }
        boolean chatOk = "ANSWERED".equals(chat.path("stopReason").asText())
                && chat.path("steps").asInt() >= 2
                && usedListDir
                && !chat.path("answer").asText().isEmpty();
        System.out.println("   " + (chatOk ? "[PASS]" : "[FAIL]") + " /chat → "
                + chat.path("stopReason").asText() + ", "
                + chat.path("steps").asInt() + " steps, "
                + chat.path("toolCalls").asInt() + " tools, list_dir=" + usedListDir);
        if (!chatOk) {
            failures++;
        }

        JsonNode reset = call(base + "/reset", "POST", "{\"session\":\"selftest\"}");
        boolean resetOk = reset.path("cleared").asBoolean();
        System.out.println("   " + (resetOk ? "[PASS]" : "[FAIL]")
                + " /reset → cleared=" + resetOk);
        if (!resetOk) {
            failures++;
        }

        // ---- 流式：SSE 帧逐条到位，最后的 final 带答案 ----
        String sse = callRaw(base + "/chat", "POST",
                "{\"session\":\"selftest-sse\",\"message\":\"看一下工作区有哪些文件\","
                        + "\"stream\":true}");
        boolean sseOk = sse.contains("\"type\":\"step\"")
                && sse.contains("\"type\":\"tool_start\"")
                && sse.contains("\"type\":\"tool\"")
                && sse.contains("\"name\":\"list_dir\"")
                && sse.contains("\"type\":\"final\"")
                && sse.contains("\"stopReason\":\"ANSWERED\"")
                && sse.trim().startsWith("data: ");
        System.out.println("   " + (sseOk ? "[PASS]" : "[FAIL]")
                + " /chat stream=true → " + sse.split("\\n\\n").length + " frames");
        if (!sseOk) {
            System.out.println("     raw: " + brief(sse, 400));
            failures++;
        }

        // ---- 并发：两个 sleep 会话 + 一个 list_dir 会话同时跑 ----
        // 串行至少 800ms；并发约 400ms。事件随 run 走（AgentListener），不应串台
        final String[] bodies = {
                "{\"session\":\"selftest-c1\",\"message\":\"先 sleep 400 毫秒\"}",
                "{\"session\":\"selftest-c2\",\"message\":\"先 sleep 400 毫秒\"}",
                "{\"session\":\"selftest-c3\",\"message\":\"看一下工作区有哪些文件\"}"};
        final JsonNode[] replies = new JsonNode[bodies.length];
        final Throwable[] errors = new Throwable[bodies.length];
        Thread[] threads = new Thread[bodies.length];
        long start = System.currentTimeMillis();
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    replies[index] = call(base + "/chat", "POST", bodies[index]);
                } catch (Throwable t) {
                    errors[index] = t;
                }
            }, "selftest-client-" + i);
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        long elapsed = System.currentTimeMillis() - start;
        boolean concurrentOk = errors[0] == null
                && errors[1] == null
                && errors[2] == null
                && elapsed < 750
                && toolNames(replies[0]).equals(java.util.Arrays.asList("sleep"))
                && toolNames(replies[1]).equals(java.util.Arrays.asList("sleep"))
                && toolNames(replies[2]).equals(java.util.Arrays.asList("list_dir"));
        System.out.println("   " + (concurrentOk ? "[PASS]" : "[FAIL]")
                + " 三会话并发 → " + elapsed + "ms, 事件: "
                + toolNames(replies[0]) + " / " + toolNames(replies[1])
                + " / " + toolNames(replies[2]));
        if (!concurrentOk) {
            System.out.println("     error: " + errors[0] + " " + errors[1] + " " + errors[2]);
            failures++;
        }
        return failures;
    }

    /** 一次响应里所有 tool 事件的工具名（用于验证事件不串台） */
    private static List<String> toolNames(JsonNode reply) {
        List<String> names = new ArrayList<String>();
        if (reply == null) {
            return names;
        }
        for (JsonNode event : reply.path("events")) {
            if ("tool".equals(event.path("type").asText())) {
                names.add(event.path("name").asText());
            }
        }
        return names;
    }

    // ---------- 夹具 ----------

    /**
     * 工作区初始化：skills 非空就跳过（使用者改过的技能不能被覆盖），
     * 否则写两个示例技能和一份素材，保证「从零目录启动」也有东西可玩。
     */
    private static void seedWorkspace(Path root, Path skillsDir) throws Exception {
        if (Files.isDirectory(skillsDir)) {
            try (Stream<Path> entries = Files.list(skillsDir)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
        }
        Files.createDirectories(skillsDir.resolve("markdown-writer"));
        Files.write(skillsDir.resolve("markdown-writer/skill.json"),
                "{\"description\":\"团队 Markdown 报告的格式与分段规范（写报告/周报时使用）\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(skillsDir.resolve("markdown-writer/SKILL.md"),
                ("# Markdown 报告规范\n"
                        + "1. 先用 list_dir 看一下工作区，再用 read_file 读素材；\n"
                        + "2. 输出固定三段：## 摘要 / ## 详情 / ## 风险；\n"
                        + "3. 不要编造素材里没有的内容。\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(skillsDir.resolve("release-notes"));
        Files.write(skillsDir.resolve("release-notes/skill.json"),
                "{\"description\":\"发布说明的格式与口径（本周变更对外发布时使用）\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(skillsDir.resolve("release-notes/SKILL.md"),
                ("# 发布说明规范\n"
                        + "1. 先读 CHANGES.md；\n"
                        + "2. 按「新增 / 修复 / 风险」三段输出，每条一行；\n"
                        + "3. 不编造条目。\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("CHANGES.md"),
                ("- 新增：计费规则支持按门店维度配置\n"
                        + "- 修复：重复提交导致凭证重复生成\n"
                        + "- 风险：历史数据未回刷，报表口径可能不一致\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 测试用模型 ----------

    /**
     * 离线脚本模型：按消息关键词选一个工具走一轮，再给固定终答。作用是把
     * 「装配 → loop → 工具 → 事件 → 响应」整条链跑通，与模型智能无关。
     */
    private static final class OfflineLlm implements LlmClient {

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools, String systemPrompt) {
            LlmMessage last = history.get(history.size() - 1);
            if ("tool".equals(last.role)) {
                return LlmReply.answer("（离线模式）工具已返回：" + brief(last.content, 120));
            }
            String message = last.content == null ? "" : last.content;
            if (message.contains("技能") && hasTool(tools, "use_skill")) {
                return LlmReply.toolCall("call_skill", "use_skill",
                        "{\"name\":\"markdown-writer\"}");
            }
            if ((message.contains("文件") || message.contains("目录"))
                    && hasTool(tools, "list_dir")) {
                return LlmReply.toolCall("call_ls", "list_dir", "{\"path\":\".\"}");
            }
            if (message.contains("sleep") && hasTool(tools, "sleep")) {
                return LlmReply.toolCall("call_sleep", "sleep", "{\"ms\":400}");
            }
            return LlmReply.answer("（离线模式）我收到了：" + message
                    + "。设置 " + AgentServer.ENV_APP_ID + " 后可接入真实模型。");
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

    /**
     * 必定阻塞指定毫秒的工具，只在回归里装配。
     *
     * <p>唯一用途是给并发断言造一个可观测的等待点：两个会话同时发它，总耗时
     * 接近单次等待就说明两个 run 真的并行（去掉会话锁之前必定是两次相加）。
     * 等待期间占的是本次 run 自己 offload 出来的 worker，不碰主循环。
     */
    private static final class SleepTool implements Tool {

        @Override
        public String name() {
            return "sleep";
        }

        @Override
        public String description() {
            return "阻塞指定毫秒后返回（仅回归用）";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\",\"properties\":{\"ms\":{\"type\":\"number\"}}}";
        }

        @Override
        public String run(String arguments) throws Exception {
            JsonNode node = JSON.readTree(arguments == null || arguments.trim().isEmpty()
                    ? "{}" : arguments);
            int ms = node.path("ms").asInt(400);
            Thread.sleep(ms);
            return "{\"slept\":" + ms + "}";
        }

    }

    // ---------- HTTP 客户端 ----------

    private static JsonNode call(String url, String method, String body) throws Exception {
        return JSON.readTree(callRaw(url, method, body));
    }

    /** 返回原始响应文本（SSE 不是 JSON，parse 不适用） */
    private static String callRaw(String url, String method, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(60000);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        String text = in == null ? "{}" : new String(readAll(in), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new IllegalStateException("HTTP " + status + ": " + text);
        }
        return text;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

    private static String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }

}
