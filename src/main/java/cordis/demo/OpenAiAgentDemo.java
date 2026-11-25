package cordis.demo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.AgentPlugin;
import cordis.agent.AgentResult;
import cordis.agent.AgentService;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.Tool;
import cordis.agent.ToolCall;
import cordis.agent.ToolComponent;
import cordis.agent.llm.OpenAiLlmClient;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.util.Maps;

/**
 * 接真实模型跑完整 agent loop：公司 AIGC 网关（OpenAI 兼容）→ 流式解析 →
 * 工具调用聚合 → 本地工具真的被调用。
 *
 * <pre>
 *   MT_AIGC_APP_ID=xxx MT_AIGC_MODEL=LongCat-Flash-Chat \
 *     java -cp target/classes:$(cat /tmp/cp.txt) cordis.demo.OpenAiAgentDemo
 * </pre>
 *
 * <p>两个 section：
 *
 * <ol>
 *   <li><b>多步 loop</b>：流式客户端 + 三个只读本地工具，模型自己决定
 *       "先列目录、再读文件、最后总结"，每一步都真的落到磁盘上；</li>
 *   <li><b>两条 wire 路径对拍</b>：同一请求分别走 SSE 流式与一次性 JSON，
 *       比对聚合出的 {@link ToolCall}（name + arguments）是否一致——这是
 *       {@code SseEvent → ToolCallDelta → ToolCallAggregator} 这条链路的
 *       正确性证明。</li>
 * </ol>
 *
 * <p>AppId 从环境变量读，不入库、不落盘：它是计费凭证，等同于密码。
 */
public final class OpenAiAgentDemo {

    private static final String DEFAULT_BASE_URL = "https://aigc.sankuai.com/v1/openai/native";

    private static final String DEFAULT_MODEL = "LongCat-Flash-Chat";

    private static final String SYSTEM_PROMPT =
            "你可以调用本地只读工具。凡涉及文件内容的问题必须真的调用工具，"
                    + "不要凭记忆猜测；一次可以同时发起多个工具调用。回答用中文，简洁。";

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiAgentDemo() {
    }

    public static void main(String[] args) throws Exception {
        final String appId = env("MT_AIGC_APP_ID", null);
        if (appId == null) {
            System.out.println("缺少环境变量 MT_AIGC_APP_ID（Friday 个人租户 AppId）");
            System.out.println("  MT_AIGC_APP_ID=xxx java -cp ... cordis.demo.OpenAiAgentDemo");
            return;
        }
        final String baseUrl = env("MT_AIGC_BASE_URL", DEFAULT_BASE_URL);
        final String model = env("MT_AIGC_MODEL", DEFAULT_MODEL);
        final String target = new File(args.length > 0 ? args[0] : "src/main/java/cordis/agent")
                .getAbsolutePath();

        System.out.println("endpoint: " + baseUrl + "/chat/completions");
        System.out.println("model   : " + model);
        System.out.println("target  : " + target);
        System.out.println();

        // 流式客户端给 agent 用；非流式那个只用于对拍
        final OpenAiLlmClient streaming =
                new OpenAiLlmClient(baseUrl, appId, model, SYSTEM_PROMPT, true, 0);
        final OpenAiLlmClient whole =
                new OpenAiLlmClient(baseUrl, appId, model, SYSTEM_PROMPT, false, 0);

        Context root = Context.root();
        final Disposable stepHook = root.on(AgentService.EVT_STEP, a -> {
            System.out.println("   [step " + a[0] + "] 可见工具 " + a[1] + " 个");
            return null;
        });
        final Disposable toolHook = root.on(AgentService.EVT_TOOL, a -> {
            System.out.println("   [tool] " + a[0] + " -> " + brief(String.valueOf(a[1]), 110));
            return null;
        });

        Fiber agentFiber = root.registry.use(AgentPlugin.INSTANCE,
                Maps.of("llm", streaming, "maxSteps", 6), null).await().join();
        Fiber listFiber = root.registry.use(new ToolComponent(new ListDirTool()),
                Maps.of(), Maps.of("agent.tools", true)).await().join();
        Fiber readFiber = root.registry.use(new ToolComponent(new ReadFileTool()),
                Maps.of(), Maps.of("agent.tools", true)).await().join();
        Fiber clockFiber = root.registry.use(new ToolComponent(new ClockTool()),
                Maps.of(), Maps.of("agent.tools", true)).await().join();

        watchdog(180);
        try {
            sectionLoop(root.get("agent"), target, model);
            sectionWire(streaming, whole, target);
        } finally {
            stepHook.dispose();
            toolHook.dispose();
            agentFiber.dispose();
            listFiber.dispose();
            readFiber.dispose();
            clockFiber.dispose();
            streaming.dispose();
            whole.dispose();
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
        }
    }

    // ---------- 1. 多步 loop ----------

    private static void sectionLoop(AgentService agent, String target, String model) {
        System.out.println("== 1. 真实 agent loop（流式 wire + 本地只读工具） ==");
        List<LlmMessage> history = new ArrayList<>();
        // 故意要求"一次调用两个目录"：同一条流里会到达 index=0/1 两组交错分片，
        // 正是 ToolCallAggregator 的按槽位归并要处理的情形
        String prompt = "先用 list_dir 一次查询这两个目录：" + target + " 和 " + target + "/llm；"
                + "再挑 ToolCallAggregator.java 用 read_file 读它开头 30 行，"
                + "最后用一句话说明这个类的作用。";
        long start = System.currentTimeMillis();
        AgentResult result = agent.run(history, prompt).completion().join();
        long cost = System.currentTimeMillis() - start;

        System.out.println("   终答: " + result.answer);
        System.out.println("   终态: " + result.stopReason + "，" + result.steps + " 轮，"
                + result.toolCalls + " 次工具");
        System.out.println("   耗时 " + cost + "ms，历史 " + history.size() + " 条 "
                + roles(history));
        long toolMessages = history.stream().filter(m -> "tool".equals(m.role)).count();
        System.out.println("   其中工具结果 " + toolMessages + " 条（模型真的调了本地工具）");
        System.out.println();
    }

    // ---------- 2. 两条 wire 路径对拍 ----------

    private static void sectionWire(OpenAiLlmClient streaming, OpenAiLlmClient whole, String target)
            throws Exception {
        System.out.println("== 2. 对拍：同一请求，SSE 流式 vs 一次性 JSON ==");
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user("用 list_dir 列出 " + target + " 目录。只调工具，不要解释。"));
        // 顺序故意与 snapshot 的字典序不同，验证声明顺序不影响解析
        List<Tool> tools = Arrays.<Tool>asList(new ListDirTool(), new ReadFileTool());

        long t0 = System.nanoTime();
        LlmReply viaStream = streaming.chat(history, tools);
        long t1 = System.nanoTime();
        LlmReply viaWhole = whole.chat(history, tools);
        long t2 = System.nanoTime();

        System.out.println("   stream: " + millis(t0, t1) + "ms -> " + describe(viaStream));
        System.out.println("   whole : " + millis(t1, t2) + "ms -> " + describe(viaWhole));
        System.out.println("   聚合结果一致: " + sameCalls(viaStream, viaWhole)
                + "（id 由服务端生成，不参与比对）");
        System.out.println();
    }

    /** 只比 name + arguments：id 每次调用都不同 */
    private static boolean sameCalls(LlmReply a, LlmReply b) {
        if (a.toolCalls.size() != b.toolCalls.size()) {
            return false;
        }
        for (int i = 0; i < a.toolCalls.size(); i++) {
            ToolCall left = a.toolCalls.get(i);
            ToolCall right = b.toolCalls.get(i);
            if (!left.name.equals(right.name) || !left.arguments.equals(right.arguments)) {
                return false;
            }
        }
        return true;
    }

    private static String describe(LlmReply reply) {
        if (!reply.hasToolCalls()) {
            return "text(" + brief(reply.content, 60) + ")";
        }
        StringBuilder out = new StringBuilder();
        for (ToolCall call : reply.toolCalls) {
            if (out.length() > 0) {
                out.append(" + ");
            }
            out.append(call.name).append(call.arguments);
        }
        return out.toString();
    }

    // ---------- 本地工具（只读，够真实又不会造成破坏） ----------

    /** 列目录 */
    private static final class ListDirTool implements Tool {

        @Override
        public String name() {
            return "list_dir";
        }

        @Override
        public String description() {
            return "列出指定目录下的文件和子目录名（不递归，只读）";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"目录的绝对路径\"}},"
                    + "\"required\":[\"path\"]}";
        }

        @Override
        public String run(String arguments) throws Exception {
            String path = JSON.readTree(arguments).path("path").asText("");
            File dir = new File(path);
            if (!dir.isDirectory()) {
                return error("not a directory: " + path);
            }
            File[] entries = dir.listFiles();
            if (entries == null) {
                return error("cannot read directory: " + path);
            }
            Arrays.sort(entries, Comparator.comparing(File::getName));
            ObjectNode out = JSON.createObjectNode();
            out.put("path", dir.getAbsolutePath());
            ArrayNode names = out.putArray("entries");
            for (File entry : entries) {
                names.add(entry.getName() + (entry.isDirectory() ? "/" : ""));
            }
            return JSON.writeValueAsString(out);
        }

    }

    /** 读文件前 N 行 */
    private static final class ReadFileTool implements Tool {

        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "读取文本文件的前若干行（只读，默认 30 行）";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"path\":{\"type\":\"string\",\"description\":\"文件绝对路径\"},"
                    + "\"lines\":{\"type\":\"integer\",\"description\":\"最多读多少行，默认 30\"}},"
                    + "\"required\":[\"path\"]}";
        }

        @Override
        public String run(String arguments) throws Exception {
            JsonNode args = JSON.readTree(arguments);
            String path = args.path("path").asText("");
            int limit = args.path("lines").asInt(30);
            if (limit <= 0) {
                limit = 30;
            }
            File file = new File(path);
            if (!file.isFile()) {
                return error("not a file: " + path);
            }
            List<String> all = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            int take = Math.min(limit, all.size());
            ObjectNode out = JSON.createObjectNode();
            out.put("path", file.getAbsolutePath());
            out.put("totalLines", all.size());
            out.put("content", String.join("\n", all.subList(0, take)));
            return JSON.writeValueAsString(out);
        }

    }

    /** 当前时间：无参工具的样板（parametersSchema 返回空对象） */
    private static final class ClockTool implements Tool {

        @Override
        public String name() {
            return "clock";
        }

        @Override
        public String description() {
            return "读取本机当前时间（带时区）";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        @Override
        public String run(String arguments) throws Exception {
            ZonedDateTime now = ZonedDateTime.now();
            ObjectNode out = JSON.createObjectNode();
            out.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            out.put("zone", now.getZone().getId());
            out.put("epochMillis", now.toInstant().toEpochMilli());
            return JSON.writeValueAsString(out);
        }

    }

    // ---------- 工具方法 ----------

    private static String error(String message) throws Exception {
        ObjectNode out = JSON.createObjectNode();
        out.put("error", message);
        return JSON.writeValueAsString(out);
    }

    private static String roles(List<LlmMessage> history) {
        StringBuilder out = new StringBuilder();
        for (LlmMessage message : history) {
            if (out.length() > 0) {
                out.append(" -> ");
            }
            out.append(message.role);
        }
        return out.toString();
    }

    private static String millis(long fromNanos, long toNanos) {
        return String.valueOf((toNanos - fromNanos) / 1_000_000);
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

    /**
     * 兜底看门狗：流式的 readTimeout 是 0（长思考不能设读超时），
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
