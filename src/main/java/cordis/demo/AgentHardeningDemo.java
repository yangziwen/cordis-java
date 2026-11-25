package cordis.demo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cordis.agent.AgentPlugin;
import cordis.agent.AgentResult;
import cordis.agent.AgentService;
import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.MemoryPlugin;
import cordis.agent.MemoryService;
import cordis.agent.Tool;
import cordis.agent.ToolCall;
import cordis.agent.ToolComponent;
import cordis.agent.llm.OpenAiLlmClient;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.sqlite.SqlitePlugin;
import cordis.sqlite.SqliteService;
import cordis.util.Maps;

/**
 * 第一批加固的验收演示：每个断言都对应一处曾经会静默出错的实现。
 *
 * <ol>
 *   <li>工具异常回填必须是合法 JSON（引号 / 换行 / 控制字符都转义）；</li>
 *   <li>一轮消息落库是事务——写一半失败不会留下半个轮次；读时若库里已有
 *       尾部残缺配对，则截断并 emit {@code memory/repaired}；</li>
 *   <li>{@code finish_reason=length / content_filter} 必须变成 IOException，
 *       不能把半截答案当终答；</li>
 *   <li>打满 maxSteps 用 {@link AgentResult.StopReason#MAX_STEPS} 表达，
 *       不再把框架的话拼成答案；</li>
 *   <li>同一轮的多个工具并发执行，总耗时≈最慢的一个，回填顺序仍按请求顺序。</li>
 * </ol>
 *
 * <p>第 3 项不需要真实网关：本地 HttpServer 回放一段 OpenAI 形状的响应即可，
 * 断言的是"我们怎么解读 finish_reason"，不是"网关怎么返回"。
 *
 * <p>任何一条不成立就以非 0 退出，可直接进回归脚本。
 */
public final class AgentHardeningDemo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentHardeningDemo() {
    }

    public static void main(String[] args) throws Exception {
        Checks checks = new Checks();

        sectionJsonEscape(checks);
        sectionTransaction(checks);
        sectionRepairTail(checks);
        sectionFinishReason(checks);
        sectionMaxSteps(checks);
        sectionParallelTools(checks);

        System.out.println();
        System.out.println("断言 " + (checks.passed + checks.failed) + " 条："
                + checks.passed + " 通过， " + checks.failed + " 失败");
        if (checks.failed > 0) {
            System.exit(1);
        }
    }

    // ---------- 1. 工具异常回填 ----------

    private static void sectionJsonEscape(Checks checks) throws Exception {
        System.out.println("== 1. 工具异常回填：引号 / 换行 / 控制字符都要变成合法 JSON ==");
        Run run = runAgent(new BoomLlm(), 3, "试一下那个会炸的工具", new BoomTool());
        String backfill = run.history.get(2).content;
        String expected = "tool boom failed: java.lang.IllegalStateException: " + BoomTool.MESSAGE;

        JsonNode node = null;
        String parseError = "";
        try {
            node = MAPPER.readTree(backfill);
        } catch (IOException e) {
            parseError = e.getMessage();
        }
        checks.check("回填文本可被 JSON 解析器接受", node != null,
                parseError.isEmpty() ? "" : "解析失败: " + parseError);

        String actual = node != null && node.has("error") ? node.get("error").asText() : null;
        checks.check("error 字段与异常原文逐字一致（换行 / 制表符原样保留）",
                expected.equals(actual), "实际: " + visible(actual));
        checks.check("loop 没被工具异常打断，仍给出终答",
                run.result.completed() && run.result.steps == 2,
                "stopReason=" + run.result.stopReason + " steps=" + run.result.steps);

        System.out.println("   回填（转义后可见）: " + visible(backfill));
        System.out.println("   终答: " + run.result.answer);
        System.out.println();
    }

    // ---------- 2a. 落库事务 ----------

    private static void sectionTransaction(Checks checks) throws Exception {
        System.out.println("== 2a. transaction：一轮消息写一半失败必须整体回滚 ==");
        Path dbFile = Files.createTempFile("cordis-tx-", ".db");
        Context root = Context.root();
        Fiber sqliteFiber = root.registry.use(SqlitePlugin.INSTANCE,
                Maps.of("url", "jdbc:sqlite:" + dbFile.toAbsolutePath()), null).await().join();
        try {
            SqliteService db = root.get("sqlite");
            db.execute("CREATE TABLE account (id TEXT PRIMARY KEY)");
            db.execute("INSERT INTO account VALUES ('a')");

            boolean threw = false;
            try {
                db.transaction(() -> {
                    db.execute("INSERT INTO account VALUES ('b')");
                    db.execute("INSERT INTO account VALUES ('c')");
                    throw new IllegalStateException("模拟写到一半失败");
                });
            } catch (IllegalStateException e) {
                threw = true;
                System.out.println("   事务抛出: " + e.getMessage());
            }
            int afterRollback = countAccounts(db);
            checks.check("异常原样穿出 transaction", threw, "");
            checks.check("工作块里的两条 INSERT 全部回滚", afterRollback == 1,
                    "回滚后 " + afterRollback + " 行（应为 1）");

            db.transaction(() -> db.execute("INSERT INTO account VALUES ('d')"));
            checks.check("事务结束后 auto-commit 已复原，后续事务照常提交",
                    countAccounts(db) == 2, "提交后 " + countAccounts(db) + " 行（应为 2）");
        } finally {
            sqliteFiber.dispose();
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
            Files.deleteIfExists(dbFile);
        }
        System.out.println();
    }

    // ---------- 2b. 读侧修复 ----------

    private static void sectionRepairTail(Checks checks) throws Exception {
        System.out.println("== 2b. load：尾部残缺的 tool_calls 配对要截断并报事件 ==");
        Path dbFile = Files.createTempFile("cordis-repair-", ".db");
        Context root = Context.root();
        Fiber sqliteFiber = root.registry.use(SqlitePlugin.INSTANCE,
                Maps.of("url", "jdbc:sqlite:" + dbFile.toAbsolutePath()), null).await().join();
        Fiber memoryFiber = null;
        Disposable hook = null;
        try {
            memoryFiber = root.registry.use(MemoryPlugin.INSTANCE,
                    Maps.of(), Maps.of("sqlite", true)).await().join();
            MemoryService memory = root.get("memory");

            // 模拟外部直接写库 / 早期没有事务的版本：assistant 声明了调用，
            // 但 tool 结果一条都没落下来
            List<LlmMessage> broken = new ArrayList<>();
            broken.add(LlmMessage.user("帮我看下目录"));
            broken.add(LlmMessage.assistant(LlmReply.toolCall("call_x", "boom", "{}")));
            memory.appendAll("broken", broken).join();

            List<LlmMessage> complete = new ArrayList<>();
            complete.add(LlmMessage.user("6 乘 7"));
            complete.add(LlmMessage.assistant(LlmReply.toolCall("call_y", "boom", "{}")));
            complete.add(LlmMessage.toolResult("call_y", "{\"product\":42}"));
            memory.appendAll("complete", complete).join();

            final AtomicReference<String> repaired = new AtomicReference<>();
            hook = root.on(MemoryService.EVT_REPAIRED, args -> {
                repaired.set(args[0] + "/" + args[1]);
                return null;
            });

            List<LlmMessage> loadedBroken = memory.load("broken").join();
            root.scheduler.awaitIdle();
            checks.check("残缺历史被截到 user 那一条", loadedBroken.size() == 1
                            && "user".equals(loadedBroken.get(0).role),
                    "读回 " + loadedBroken.size() + " 条: " + loadedBroken);
            checks.check("memory/repaired 事件带 (sessionId, 丢弃条数)",
                    "broken/1".equals(repaired.get()), "实际 " + repaired.get());

            repaired.set(null);
            List<LlmMessage> loadedComplete = memory.load("complete").join();
            root.scheduler.awaitIdle();
            checks.check("配对完整的三条原样读回", loadedComplete.size() == 3,
                    "读回 " + loadedComplete.size() + " 条");
            checks.check("完整历史不触发 repaired", repaired.get() == null,
                    "实际事件 " + repaired.get());
        } finally {
            if (hook != null) {
                hook.dispose();
            }
            if (memoryFiber != null) {
                memoryFiber.dispose();
            }
            sqliteFiber.dispose();
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
            Files.deleteIfExists(dbFile);
        }
        System.out.println();
    }

    // ---------- 3. finish_reason ----------

    private static void sectionFinishReason(Checks checks) throws Exception {
        System.out.println("== 3. finish_reason：截断 / 内容拦截必须报错，stop 才返回 ==");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/whole/chat/completions",
                exchange -> respond(exchange, "application/json", wholeBody("length")));
        server.createContext("/stop/chat/completions",
                exchange -> respond(exchange, "application/json", wholeBody("stop")));
        server.createContext("/stream/chat/completions",
                exchange -> respond(exchange, "text/event-stream", lengthStreamBody()));
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "hardening-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user("说一段长话"));

        OpenAiLlmClient whole = new OpenAiLlmClient(
                baseUrl + "/whole", "test-key", "test-model", false, 0);
        OpenAiLlmClient stop = new OpenAiLlmClient(
                baseUrl + "/stop", "test-key", "test-model", false, 0);
        OpenAiLlmClient streaming = new OpenAiLlmClient(
                baseUrl + "/stream", "test-key", "test-model", true, 0);
        try {
            checks.check("非流式 finish_reason=length -> IOException",
                    rejectedWith(whole, history, "finish_reason=length"), describe(whole, history));

            LlmReply ok = stop.chat(history, new ArrayList<>(), null);
            checks.check("finish_reason=stop 正常返回内容", "半截答案".equals(ok.content),
                    "实际: " + ok.content);

            checks.check("流式 finish_reason=length -> IOException",
                    rejectedWith(streaming, history, "finish_reason=length"),
                    describe(streaming, history));
        } finally {
            whole.dispose();
            stop.dispose();
            streaming.dispose();
            server.stop(0);
        }
        System.out.println();
    }

    private static boolean rejectedWith(OpenAiLlmClient client, List<LlmMessage> history,
                                        String needle) {
        try {
            client.chat(history, new ArrayList<>(), null);
            return false;
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            System.out.println("   拒绝原因: " + message);
            return e instanceof IOException && message.contains(needle);
        }
    }

    private static String describe(OpenAiLlmClient client, List<LlmMessage> history) {
        try {
            client.chat(history, new ArrayList<>(), null);
            return "没有抛异常";
        } catch (Exception e) {
            return "抛出 " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String wholeBody(String finishReason) {
        return "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"半截答案\"},"
                + "\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    private static String lengthStreamBody() {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半截\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static void respond(HttpExchange exchange, String contentType, String payload)
            throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
            out.flush();
        }
        exchange.close();
    }

    // ---------- 4. maxSteps ----------

    private static void sectionMaxSteps(Checks checks) throws Exception {
        System.out.println("== 4. 打满 maxSteps：stopReason=MAX_STEPS，不再伪装成答案 ==");
        Run run = runAgent(new LoopLlm(), 3, "一直算下去", new EchoTool());
        checks.check("stopReason = MAX_STEPS",
                run.result.stopReason == AgentResult.StopReason.MAX_STEPS,
                "实际 " + run.result.stopReason);
        checks.check("steps 与 toolCalls 都等于 maxSteps",
                run.result.steps == 3 && run.result.toolCalls == 3,
                "steps=" + run.result.steps + " toolCalls=" + run.result.toolCalls);
        checks.check("completed() = false，answer 为空而不是\"框架的抱怨\"",
                !run.result.completed() && run.result.answer.isEmpty(),
                "实际 " + run.result);
        System.out.println("   toString: " + run.result);
        System.out.println();
    }

    // ---------- 5. 同轮并发 ----------

    private static void sectionParallelTools(Checks checks) throws Exception {
        System.out.println("== 5. 同轮三个工具并发（300/200/100ms） ==");
        Run run = runAgent(new TripleLlm(), 3, "并行跑 a b c", new SnoozeTool());

        List<String> tags = new ArrayList<>();
        for (int i = 2; i <= 4; i++) {
            tags.add(MAPPER.readTree(run.history.get(i).content).get("tag").asText());
        }
        checks.check("回填顺序与 tool_calls 声明顺序一致（a,b,c）",
                Arrays.asList("a", "b", "c").equals(tags), "实际 " + tags);
        checks.check("总耗时接近最慢的 300ms 而非串行的 600ms",
                run.elapsedMs < 450, "实际 " + run.elapsedMs + "ms");
        checks.check("三个工具的结果都进了历史并触发终答",
                run.result.completed() && run.result.toolCalls == 3,
                "实际 " + run.result);
        System.out.println("   耗时 " + run.elapsedMs + "ms（串行下限约 600ms）");
        System.out.println();
    }

    // ---------- 装配 ----------

    private static Run runAgent(LlmClient llm, int maxSteps, String prompt, Tool... tools)
            throws Exception {
        Context root = Context.root();
        List<Fiber> fibers = new ArrayList<>();
        try {
            fibers.add(root.registry.use(AgentPlugin.INSTANCE,
                    Maps.of("llm", llm, "maxSteps", maxSteps), null).await().join());
            for (Tool tool : tools) {
                fibers.add(root.registry.use(new ToolComponent(tool), Maps.of(),
                        Maps.of("agent.tools", true)).await().join());
            }
            AgentService agent = root.get("agent");
            List<LlmMessage> history = new ArrayList<>();
            long start = System.currentTimeMillis();
            AgentResult result = agent.run(history, prompt).completion().join();
            long elapsed = System.currentTimeMillis() - start;
            return new Run(history, result, elapsed);
        } finally {
            for (Fiber fiber : fibers) {
                fiber.dispose();
            }
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
        }
    }

    private static int countAccounts(SqliteService db) {
        return ((Number) db.queryOne("SELECT COUNT(*) AS n FROM account").get("n")).intValue();
    }

    private static String visible(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n':
                    out.append("\\n");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("·");
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static LlmMessage last(List<LlmMessage> history) {
        return history.get(history.size() - 1);
    }

    // ---------- 夹具 ----------

    /** 一次 run 的产物：历史、终态、耗时（只计 loop 本身，不含装配） */
    private static final class Run {

        final List<LlmMessage> history;

        final AgentResult result;

        final long elapsedMs;

        Run(List<LlmMessage> history, AgentResult result, long elapsedMs) {
            this.history = history;
            this.result = result;
            this.elapsedMs = elapsedMs;
        }

    }

    private static final class Checks {

        int passed;

        int failed;

        void check(String label, boolean ok, String detail) {
            if (ok) {
                passed++;
                System.out.println("   [PASS] " + label);
            } else {
                failed++;
                System.out.println("   [FAIL] " + label + (detail.isEmpty() ? "" : " —— " + detail));
            }
        }

    }

    /** 第一步请求 boom，第二步（看到工具错误后）给终答 */
    private static final class BoomLlm implements LlmClient {

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools, String systemPrompt) {
            if ("tool".equals(last(history).role)) {
                return LlmReply.answer("工具报错了，我改道");
            }
            return LlmReply.toolCall("call_boom", "boom", "{}");
        }

    }

    /** 永远请求工具：用来撞 maxSteps */
    private static final class LoopLlm implements LlmClient {

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools, String systemPrompt) {
            return LlmReply.toolCall("call_" + history.size(), "echo", "{}");
        }

    }

    /** 一轮请求三个工具，故意让最慢的排在最前 */
    private static final class TripleLlm implements LlmClient {

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools, String systemPrompt) {
            if ("tool".equals(last(history).role)) {
                return LlmReply.answer("三个都回来了");
            }
            return LlmReply.withToolCalls(Arrays.asList(
                    new ToolCall("call_a", "snooze", "{\"tag\":\"a\",\"ms\":300}"),
                    new ToolCall("call_b", "snooze", "{\"tag\":\"b\",\"ms\":200}"),
                    new ToolCall("call_c", "snooze", "{\"tag\":\"c\",\"ms\":100}")));
        }

    }

    private static final class BoomTool implements Tool {

        static final String MESSAGE = "坏的参数: \"k\":1\n第二行\t制表\u0001结束";

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "总是抛异常，用于验证错误回填的转义";
        }

        @Override
        public String parametersSchema() {
            return null;
        }

        @Override
        public String run(String arguments) {
            throw new IllegalStateException(MESSAGE);
        }

    }

    private static final class EchoTool implements Tool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "原样回显参数";
        }

        @Override
        public String parametersSchema() {
            return null;
        }

        @Override
        public String run(String arguments) {
            return "{\"ok\":true}";
        }

    }

    /** 睡 ms 毫秒再回显 tag：耗时差用来判断是否真并发 */
    private static final class SnoozeTool implements Tool {

        @Override
        public String name() {
            return "snooze";
        }

        @Override
        public String description() {
            return "睡一会儿再返回 tag";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"tag\":{\"type\":\"string\"},\"ms\":{\"type\":\"number\"}}}";
        }

        @Override
        public String run(String arguments) throws Exception {
            JsonNode args = MAPPER.readTree(arguments);
            String tag = args.get("tag").asText();
            long ms = args.get("ms").asLong();
            Thread.sleep(ms);
            return "{\"tag\":\"" + tag + "\",\"slept\":" + ms + "}";
        }

    }

}
