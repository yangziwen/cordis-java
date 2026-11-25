package cordis.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cordis.agent.AgentListener;
import cordis.agent.AgentPlugin;
import cordis.agent.AgentResult;
import cordis.agent.AgentService;
import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.SystemProvider;
import cordis.agent.Tool;
import cordis.agent.ToolComponent;
import cordis.agent.ToolService;
import cordis.agent.fs.FileSystemTools;
import cordis.agent.http.HttpTools;
import cordis.agent.llm.OpenAiLlmClient;
import cordis.agent.skill.SkillTools;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.fs.ApprovalGate;
import cordis.fs.FileSystemPlugin;
import cordis.http.HttpAccessPolicy;
import cordis.http.HttpPlugin;
import cordis.skill.SkillPlugin;
import cordis.skill.SkillService;
import cordis.util.Maps;

/**
 * 可独立运行的 agent 服务：一个进程装好 fs + skills + agent 三块能力，
 * 用 HTTP 暴露对话接口。配套控制台客户端见 {@link AgentCli}。
 *
 * <pre>
 *   export MT_AIGC_APP_ID=xxx
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentServer
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentServer --port 9000 \
 *        --root ~/agent-ws --allow-write
 *
 *   # 另一个终端
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli
 * </pre>
 *
 * <h3>接口</h3>
 *
 * <ul>
 *   <li>{@code GET /health} → 模型、可见工具、技能数、写入策略、会话数</li>
 *   <li>{@code POST /chat} {"session":"可选","message":"...","stream":false} →
 *       {"session","answer","stopReason","steps","toolCalls","elapsedMs","events"}</li>
 *   <li>{@code POST /chat} 加 {@code "stream":true}（或请求头
 *       {@code Accept: text/event-stream}）→ 逐帧
 *       {@code data: {type:step|tool_start|tool|final|error,...}}</li>
 *   <li>{@code POST /reset} {"session":"..."} → 清空该会话历史</li>
 * </ul>
 *
 * <h3>并发模型</h3>
 *
 * <p>锁只护住单个会话的 {@code history}：同一会话的请求串行（历史是可变
 * 列表），不同会话真并发。过程事件走 {@link AgentListener}，随本次 run 走，
 * 天然按次隔离，不需要全局串行。
 *
 * <h3>网络访问</h3>
 *
 * <p>默认<b>不装</b> HTTP 工具：一个能自拟 URL 的模型等于把内网访问权交出去。
 * 需要联网时显式给白名单（{@code --allow-host}，可重复），此时挂上
 * {@code http_get} / {@code http_post}；{@link HttpAccessPolicy} 对<b>每一跳
 * 重定向</b>重新校验，堵住「白名单地址 302 到内网」这条路。
 *
 * <p>{@code --allow-host '*'} 意为放行<b>全部主机</b>（仍只限 http/https），
 * 只用于本地调试：此时 {@code /health} 的 {@code httpScope} 会是 {@code all}，
 * banner 也会明确提示。
 *
 * <h3>有意保留的边界</h3>
 *
 * <ul>
 *   <li>历史在内存：进程重启即丢。要持久化请换 {@code ConversationService}；</li>
 *   <li>事件级流式而非 token 级：SSE 推 step/tool，模型答案一轮结束整段吐出；</li>
 *   <li>写入默认拒绝：{@link ApprovalGate} 默认 deny-all，需显式
 *       {@code --allow-write} 或注入自定义关口——文件系统是少数「写错就真的
 *       改了用户数据」的能力，不给静默放行的默认值。</li>
 * </ul>
 *
 * <h3>扩展点</h3>
 *
 * <p>本类只负责「装配 + 协议」，不含业务工具：宿主通过
 * {@link Config#tool(Tool)} 注入自己的工具，通过
 * {@link Config#initializer(WorkspaceInitializer)} 预置工作区（模板技能、
 * 素材文件），通过 {@link Config#llm(LlmClient)} 换模型客户端。
 * 回归与自测见 {@code cordis.demo.AgentServerDemo}。
 */
public final class AgentServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 8787;

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final String DEFAULT_BASE_URL = "https://aigc.sankuai.com/v1/openai/native";

    public static final String DEFAULT_MODEL = "LongCat-Flash-Chat";

    public static final String ENV_APP_ID = "MT_AIGC_APP_ID";

    public static final String ENV_BASE_URL = "MT_AIGC_BASE_URL";

    public static final String ENV_MODEL = "MT_AIGC_MODEL";

    /** 单条工具结果在事件里的截断长度 */
    private static final int EVENT_RESULT_MAX = 300;

    /** 单个请求体上限，挡住「一条 curl 喂 500MB」这种内存事故 */
    private static final int MAX_REQUEST_BYTES = 1 << 20;

    private static final int HTTP_THREADS = 16;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE_SYSTEM =
            "你可以调用工具。system 里如果有技能清单，遇到匹配的任务必须先 use_skill"
                    + " 加载该技能的正文，再严格按正文的指引操作；不得仅凭清单里的描述直接动手。"
                    + "回答用中文，简洁。";

    private final Config config;

    private final List<Fiber> fibers = new ArrayList<Fiber>();

    private final Map<String, List<LlmMessage>> sessions =
            new ConcurrentHashMap<String, List<LlmMessage>>();

    private final CountDownLatch stopped = new CountDownLatch(1);

    private Context ctx;

    private HttpServer http;

    private LlmClient llm;

    /** 只关闭自己创建的模型客户端；外部注入的由宿主负责 */
    private boolean ownsLlm;

    private boolean closed;

    public AgentServer(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    // ---------- 生命周期 ----------

    /**
     * 装配全部能力并开始监听。失败时自行回滚，不留下半启动的进程。
     *
     * @return this，便于链式调用
     */
    public AgentServer start() throws Exception {
        final Path root = config.root().toAbsolutePath();
        final Path skillsDir = config.skillsDir() == null
                ? root.resolve("skills") : config.skillsDir().toAbsolutePath();
        Files.createDirectories(root);
        Files.createDirectories(skillsDir);
        if (config.initializer() != null) {
            config.initializer().init(root, skillsDir);
        }

        try {
            ctx = Context.root();
            ownsLlm = config.llm() == null;
            llm = ownsLlm ? createLlm(config) : config.llm();

            fibers.add(ctx.registry.use(FileSystemPlugin.INSTANCE,
                    Maps.of("root", root.toString(), "approval", approval()),
                    null).await().join());
            fibers.add(ctx.registry.use(SkillPlugin.INSTANCE,
                    Maps.of("root", skillsDir.toString()), null).await().join());
            final SystemProvider system = () -> {
                SkillService current = ctx.get("skills");
                String section = current == null ? "" : current.systemSection();
                String base = config.system() == null ? BASE_SYSTEM : config.system();
                return section.isEmpty() ? base : base + "\n\n" + section;
            };
            fibers.add(ctx.registry.use(AgentPlugin.INSTANCE,
                    Maps.of("llm", llm, "maxSteps", config.maxSteps(), "system", system),
                    null).await().join());
            fibers.add(ctx.registry.use(FileSystemTools.INSTANCE,
                    Maps.of(), Maps.of("filesystem", true, "agent.tools", true))
                    .await().join());
            fibers.add(ctx.registry.use(SkillTools.INSTANCE,
                    Maps.of(), Maps.of("skills", true, "agent.tools", true)).await().join());
            // http 工具按策略装配：没配白名单就不装——模型看不到用不了的工具，
            // 也少占 system 里的 function 定义预算
            if (config.httpAccess() != null) {
                fibers.add(ctx.registry.use(HttpPlugin.INSTANCE, Maps.of(), null)
                        .await().join());
                fibers.add(ctx.registry.use(HttpTools.INSTANCE,
                        Maps.of("policy", config.httpAccess(),
                                "timeoutMs", config.httpTimeoutMs(),
                                "maxResponseBytes", config.httpMaxResponseBytes()),
                        Maps.of("http", true, "agent.tools", true)).await().join());
            }
            for (Tool tool : config.tools()) {
                fibers.add(ctx.registry.use(new ToolComponent(tool), Maps.of(),
                        Maps.of("agent.tools", true)).await().join());
            }

            http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            http.createContext("/health", this::handleHealth);
            http.createContext("/chat", this::handleChat);
            http.createContext("/reset", this::handleReset);
            http.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS, r -> {
                Thread thread = new Thread(r, "agent-server-http");
                thread.setDaemon(true);
                return thread;
            }));
            http.start();
            return this;
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** 实际监听端口；配置为 0 时由系统分配，这里给真实值 */
    public int port() {
        return http == null ? config.port() : http.getAddress().getPort();
    }

    /** 装配后的根上下文，供宿主按需扩展（此时全部服务已 ACTIVE） */
    public Context context() {
        return ctx;
    }

    /** 阻塞到 {@link #close()} 被调用 */
    public void await() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (http != null) {
            http.stop(0);
        }
        for (Fiber fiber : fibers) {
            fiber.dispose();
        }
        fibers.clear();
        if (ctx != null) {
            ctx.scheduler.awaitIdle();
            ctx.scheduler.shutdown();
        }
        if (ownsLlm && llm instanceof Disposable) {
            ((Disposable) llm).dispose();
        }
        sessions.clear();
        stopped.countDown();
    }

    // ---------- HTTP ----------

    private void handleHealth(HttpExchange exchange) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("status", "ok");
            body.put("model", config.modelName());
            body.put("root", config.root().toAbsolutePath().toString());
            body.put("skills", skillCount());
            body.put("sessions", sessions.size());
            body.put("write", config.approval() != null ? "custom"
                    : (config.allowWrite() ? "allowed" : "denied"));
            HttpAccessPolicy httpAccess = config.httpAccess();
            body.put("http", httpAccess == null ? "disabled" : "enabled");
            if (httpAccess != null) {
                body.put("httpScope", httpAccess.open() ? "all" : "allowlist");
            }
            ArrayNode names = body.putArray("tools");
            for (Tool tool : visibleTools()) {
                names.add(tool.name());
            }
            write(exchange, 200, JSON.writeValueAsBytes(body));
        } catch (Exception e) {
            writeError(exchange, 500, messageOf(e));
        }
    }

    private void handleChat(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "use POST");
            return;
        }
        try {
            JsonNode request = JSON.readTree(readBody(exchange));
            String message = text(request, "message");
            if (message.isEmpty()) {
                writeError(exchange, 400, "missing \"message\"");
                return;
            }
            String session = text(request, "session");
            if (session.isEmpty()) {
                session = UUID.randomUUID().toString().substring(0, 8);
            }
            List<LlmMessage> history = session(session);
            if (history == null) {
                writeError(exchange, 503, "too many sessions (maxSessions="
                        + config.maxSessions() + ")");
                return;
            }
            boolean stream = request.path("stream").asBoolean(false)
                    || acceptsEventStream(exchange);
            if (stream) {
                chatStream(exchange, history, message, session);
            } else {
                chatOnce(exchange, history, message, session);
            }
        } catch (Exception e) {
            writeError(exchange, 500, messageOf(e));
        }
    }

    private void handleReset(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeError(exchange, 405, "use POST");
            return;
        }
        try {
            JsonNode request = JSON.readTree(readBody(exchange));
            String session = text(request, "session");
            boolean cleared = !session.isEmpty() && sessions.remove(session) != null;
            ObjectNode body = JSON.createObjectNode();
            body.put("session", session);
            body.put("cleared", cleared);
            write(exchange, 200, JSON.writeValueAsBytes(body));
        } catch (Exception e) {
            writeError(exchange, 500, messageOf(e));
        }
    }

    // ---------- 两种响应 ----------

    /** 一次性 JSON：过程事件随响应一起返回，适合脚本与 curl */
    private void chatOnce(HttpExchange exchange, List<LlmMessage> history,
                          String message, String session) throws Exception {
        final List<Map<String, Object>> collected = new ArrayList<Map<String, Object>>();
        final long start = System.currentTimeMillis();
        AgentResult result;
        synchronized (history) {
            result = agent().run(history, message, collector(collected))
                    .completion().join();
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("session", session);
        body.put("answer", result.answer == null ? "" : result.answer);
        body.put("stopReason", String.valueOf(result.stopReason));
        body.put("steps", result.steps);
        body.put("toolCalls", result.toolCalls);
        body.put("elapsedMs", System.currentTimeMillis() - start);
        ArrayNode events = body.putArray("events");
        for (Map<String, Object> event : collected) {
            events.add(JSON.valueToTree(event));
        }
        write(exchange, 200, JSON.writeValueAsBytes(body));
    }

    /** SSE：过程事件立刻落 socket，最后一条 final 收尾 */
    private void chatStream(HttpExchange exchange, List<LlmMessage> history,
                            String message, String session) throws Exception {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);              // 0 = chunked，长度未知
        final OutputStream out = exchange.getResponseBody();
        final Object writeLock = new Object();
        final long start = System.currentTimeMillis();
        try {
            AgentListener listener = new AgentListener() {
                @Override
                public void onStep(int step, int visibleTools) {
                    sseWrite(out, writeLock, stepEvent(step, visibleTools));
                }

                @Override
                public void onToolStart(String name, String arguments) {
                    Map<String, Object> event = event("tool_start");
                    event.put("name", name);
                    event.put("arguments", brief(arguments, EVENT_RESULT_MAX));
                    sseWrite(out, writeLock, event);
                }

                @Override
                public void onToolResult(String name, String result) {
                    Map<String, Object> event = event("tool");
                    event.put("name", name);
                    event.put("result", brief(result, EVENT_RESULT_MAX));
                    sseWrite(out, writeLock, event);
                }
            };
            AgentResult result;
            synchronized (history) {
                result = agent().run(history, message, listener).completion().join();
            }
            Map<String, Object> finalEvent = event("final");
            finalEvent.put("session", session);
            finalEvent.put("answer", result.answer == null ? "" : result.answer);
            finalEvent.put("stopReason", String.valueOf(result.stopReason));
            finalEvent.put("steps", result.steps);
            finalEvent.put("toolCalls", result.toolCalls);
            finalEvent.put("elapsedMs", System.currentTimeMillis() - start);
            sseWrite(out, writeLock, finalEvent);
        } catch (Exception e) {
            Map<String, Object> error = event("error");
            error.put("message", messageOf(e));
            sseWrite(out, writeLock, error);
        } finally {
            synchronized (writeLock) {
                try {
                    out.flush();
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                exchange.close();
            }
        }
    }

    /**
     * 一次性响应的事件收集器。
     *
     * <p>不需要加锁：{@link AgentListener} 的回调都在本次 run 的同一个 worker
     * 线程上按顺序发生（见其线程契约）。
     */
    private static AgentListener collector(final List<Map<String, Object>> sink) {
        return new AgentListener() {
            @Override
            public void onStep(int step, int visibleTools) {
                sink.add(stepEvent(step, visibleTools));
            }

            @Override
            public void onToolResult(String name, String result) {
                Map<String, Object> event = event("tool");
                event.put("name", name);
                event.put("result", brief(result, EVENT_RESULT_MAX));
                sink.add(event);
            }
        };
    }

    private static Map<String, Object> stepEvent(int step, int visibleTools) {
        Map<String, Object> event = event("step");
        event.put("index", step);
        event.put("tools", visibleTools);
        return event;
    }

    private static Map<String, Object> event(String type) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        return event;
    }

    /** 写一条 SSE 帧；客户端断开后写失败不影响 run 跑完 */
    private static void sseWrite(OutputStream out, Object writeLock,
                                 Map<String, Object> payload) {
        try {
            byte[] bytes = ("data: " + JSON.writeValueAsString(payload) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                out.write(bytes);
                out.flush();
            }
        } catch (Exception e) {
            // 忽略：run 无法中途取消，继续跑完只是浪费自己的时间
        }
    }

    // ---------- 内部装配 ----------

    private AgentService agent() {
        AgentService service = ctx == null ? null : ctx.get("agent");
        if (service == null) {
            throw new IllegalStateException("agent service is not ready");
        }
        return service;
    }

    private List<Tool> visibleTools() {
        ToolService tools = ctx == null ? null : ctx.get("agent.tools");
        return tools == null ? new ArrayList<Tool>() : tools.snapshot();
    }

    private int skillCount() {
        SkillService skills = ctx == null ? null : ctx.get("skills");
        return skills == null ? 0 : skills.list().size();
    }

    private ApprovalGate approval() {
        if (config.approval() != null) {
            return config.approval();
        }
        return config.allowWrite() ? request -> true : ApprovalGate.denyAll();
    }

    private static LlmClient createLlm(Config config) {
        if (config.appId() == null || config.appId().trim().isEmpty()) {
            throw new IllegalStateException("missing model credential: set environment"
                    + " variable " + ENV_APP_ID + ", or pass a LlmClient via"
                    + " Config.llm(...)");
        }
        return new OpenAiLlmClient(config.baseUrl(), config.appId(), config.model(), true, 0);
    }

    /** 会话历史；超过容量上限返回 null（调用方回 503） */
    private List<LlmMessage> session(String id) {
        List<LlmMessage> history = sessions.get(id);
        if (history != null) {
            return history;
        }
        if (sessions.size() >= config.maxSessions()) {
            return null;
        }
        return sessions.computeIfAbsent(id, key -> new ArrayList<LlmMessage>());
    }

    // ---------- HTTP 工具 ----------

    private byte[] readBody(HttpExchange exchange) throws Exception {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("request body exceeds "
                        + MAX_REQUEST_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean acceptsEventStream(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private static void write(HttpExchange exchange, int status, byte[] body) throws Exception {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void writeError(HttpExchange exchange, int status, String message) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("error", message == null ? "" : message);
            write(exchange, status, JSON.writeValueAsBytes(body));
        } catch (Exception ignored) {
            exchange.close();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }

    /**
     * 取最内层原因的消息：{@code Promise.join()} 会把异常包成
     * {@code IllegalStateException("promise rejected: ...")}，直接回给客户端
     * 会丢掉真正可读的那句话。
     */
    private static String messageOf(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    // ---------- 命令行 ----------

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[agent-server] " + e.getMessage());
            System.err.println("[agent-server] 用 --help 看用法");
            System.exit(2);
            return;
        }
        if (config.help()) {
            printUsage();
            return;
        }
        final AgentServer server = new AgentServer(config);
        try {
            server.start();
        } catch (Exception e) {
            System.err.println("[agent-server] 启动失败：" + messageOf(e));
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close,
                "agent-server-shutdown"));
        printBanner(server);
        server.await();
    }

    private static void printBanner(AgentServer server) {
        System.out.println("[agent-server] listening on http://"
                + server.config.host() + ":" + server.port());
        System.out.println("[agent-server] workspace: "
                + server.config.root().toAbsolutePath());
        System.out.println("[agent-server] model: " + server.config.modelName());
        System.out.println("[agent-server] write: "
                + (server.config.approval() != null ? "custom gate"
                        : (server.config.allowWrite() ? "allowed (--allow-write)"
                                : "denied (只读；需要写入时加 --allow-write)")));
        System.out.println("[agent-server] skills: " + server.skillCount()
                + "  tools: " + server.visibleTools().size());
        HttpAccessPolicy httpAccess = server.config.httpAccess();
        String httpLine;
        if (httpAccess == null) {
            httpLine = "off (用 --allow-host 放行后启用)";
        } else if (httpAccess.open()) {
            httpLine = "on (全部主机放行 —— 模型可访问任意 http/https 地址，仅建议本地调试)";
        } else {
            httpLine = "on (白名单逐跳校验)";
        }
        System.out.println("[agent-server] http tools: " + httpLine);
        if (server.skillCount() == 0) {
            System.out.println("[agent-server] 提示：把技能放到 "
                    + server.config.root().toAbsolutePath().resolve("skills")
                    + "/<名字>/SKILL.md，重启后出现在 system 清单里");
        }
        System.out.println();
        System.out.println("试试：");
        System.out.println("  java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli");
        System.out.println("  或 curl -s -X POST http://" + server.config.host() + ":"
                + server.port() + "/chat \\");
        System.out.println("       -H 'Content-Type: application/json' \\");
        System.out.println("       -d '{\"message\":\"看一下工作区有哪些文件\"}'");
        System.out.println("Ctrl-C 退出");
    }

    private static void printUsage() {
        System.out.println("用法: cordis.app.AgentServer [选项]");
        System.out.println();
        System.out.println("  --port <n>        监听端口，默认 " + DEFAULT_PORT + "（0 = 系统分配）");
        System.out.println("  --bind <host>     绑定地址，默认 " + DEFAULT_HOST);
        System.out.println("  --root <dir>      工作区目录，默认 ./agent-workspace");
        System.out.println("  --skills <dir>    技能目录，默认 <root>/skills");
        System.out.println("  --max-steps <n>   单次对话的模型轮数上限，默认 8");
        System.out.println("  --max-sessions <n> 内存会话数上限，默认 512");
        System.out.println("  --model <name>    模型名，默认 " + DEFAULT_MODEL);
        System.out.println("  --base-url <url>  OpenAI 兼容网关，默认 " + DEFAULT_BASE_URL);
        System.out.println("  --allow-write     放行文件写入（默认为只读：写操作被拒绝）");
        System.out.println("  --allow-host <h>  允许模型访问的主机，可重复（如 api.example.com、"
                + "*.corp.com；'*' = 放行全部主机，仅建议本地调试）；不写则不装配 http 工具");
        System.out.println("  --help            显示本帮助");
        System.out.println();
        System.out.println("凭证只从环境变量读：" + ENV_APP_ID + " / " + ENV_BASE_URL
                + " / " + ENV_MODEL);
    }

    // ---------- 配置 ----------

    /**
     * 服务配置。全部字段有可用默认值，链式设置。
     *
     * <p>命令行入口用 {@link #parse(String[])} 组装；宿主直接 new 后按需设置。
     */
    public static final class Config {

        private int port = DEFAULT_PORT;

        private String host = DEFAULT_HOST;

        private Path root = Paths.get("agent-workspace");

        private Path skillsDir;

        private String appId;

        private String baseUrl = DEFAULT_BASE_URL;

        private String model = DEFAULT_MODEL;

        private int maxSteps = 8;

        private int maxSessions = 512;

        private boolean allowWrite;

        private HttpAccessPolicy httpAccess;

        private int httpTimeoutMs = 15000;

        private int httpMaxResponseBytes = 65536;

        private String system;

        private LlmClient llm;

        private ApprovalGate approval;

        private WorkspaceInitializer initializer;

        private final List<Tool> tools = new ArrayList<Tool>();

        private boolean help;

        public static Config parse(String[] args) {
            Config config = new Config();
            config.appId = env(ENV_APP_ID, null);
            config.baseUrl = env(ENV_BASE_URL, DEFAULT_BASE_URL);
            config.model = env(ENV_MODEL, DEFAULT_MODEL);
            final List<String> allowedHosts = new ArrayList<String>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    config.help = true;
                } else if ("--allow-write".equals(arg)) {
                    config.allowWrite = true;
                } else if ("--allow-host".equals(arg)) {
                    allowedHosts.add(value(args, ++i, arg));
                } else if ("--port".equals(arg)) {
                    config.port = number(arg, value(args, ++i, arg));
                } else if ("--bind".equals(arg)) {
                    config.host = value(args, ++i, arg);
                } else if ("--root".equals(arg)) {
                    config.root = Paths.get(value(args, ++i, arg));
                } else if ("--skills".equals(arg)) {
                    config.skillsDir = Paths.get(value(args, ++i, arg));
                } else if ("--max-steps".equals(arg)) {
                    config.maxSteps = number(arg, value(args, ++i, arg));
                } else if ("--max-sessions".equals(arg)) {
                    config.maxSessions = number(arg, value(args, ++i, arg));
                } else if ("--model".equals(arg)) {
                    config.model = value(args, ++i, arg);
                } else if ("--base-url".equals(arg)) {
                    config.baseUrl = value(args, ++i, arg);
                } else {
                    throw new IllegalArgumentException("unknown option: " + arg);
                }
            }
            if (!allowedHosts.isEmpty()) {
                config.httpAccess = HttpAccessPolicy.hosts(
                        allowedHosts.toArray(new String[allowedHosts.size()]));
            }
            return config;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }

        private static int number(String option, String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " expects a number, got: " + text);
            }
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }

        public Config port(int port) {
            this.port = port;
            return this;
        }

        public Config host(String host) {
            this.host = host;
            return this;
        }

        public Config root(Path root) {
            this.root = root;
            return this;
        }

        public Config skillsDir(Path skillsDir) {
            this.skillsDir = skillsDir;
            return this;
        }

        public Config appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Config baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Config model(String model) {
            this.model = model;
            return this;
        }

        public Config maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Config maxSessions(int maxSessions) {
            this.maxSessions = maxSessions;
            return this;
        }

        /** 放行一切文件写入；等价于 {@code approval(request -> true)} */
        public Config allowWrite(boolean allowWrite) {
            this.allowWrite = allowWrite;
            return this;
        }

        /**
         * 启用 http 工具并给定访问策略；传 null 表示不装配 http 工具。
         * 策略对每一跳重定向都会重新求值。
         */
        public Config httpAccess(HttpAccessPolicy policy) {
            this.httpAccess = policy;
            return this;
        }

        /** 便捷写法：按主机白名单启用 http 工具，语义见 {@link HttpAccessPolicy#hosts} */
        public Config httpHosts(String... hosts) {
            this.httpAccess = HttpAccessPolicy.hosts(hosts);
            return this;
        }

        public Config httpTimeoutMs(int httpTimeoutMs) {
            this.httpTimeoutMs = httpTimeoutMs;
            return this;
        }

        public Config httpMaxResponseBytes(int httpMaxResponseBytes) {
            this.httpMaxResponseBytes = httpMaxResponseBytes;
            return this;
        }

        /** 覆盖默认 system 指令（技能清单仍会追加在后面） */
        public Config system(String system) {
            this.system = system;
            return this;
        }

        /** 外部注入模型客户端；注入后由宿主负责关闭它 */
        public Config llm(LlmClient llm) {
            this.llm = llm;
            return this;
        }

        /** 自定义写审批关口；优先级高于 {@link #allowWrite(boolean)} */
        public Config approval(ApprovalGate approval) {
            this.approval = approval;
            return this;
        }

        /** 工作区初始化钩子：首次启动预置模板技能、素材文件等 */
        public Config initializer(WorkspaceInitializer initializer) {
            this.initializer = initializer;
            return this;
        }

        /** 追加一个业务工具（与内置的 fs/skill 工具并列注册） */
        public Config tool(Tool tool) {
            this.tools.add(tool);
            return this;
        }

        public int port() {
            return port;
        }

        public String host() {
            return host;
        }

        public Path root() {
            return root;
        }

        public Path skillsDir() {
            return skillsDir;
        }

        public String appId() {
            return appId;
        }

        public String baseUrl() {
            return baseUrl;
        }

        public String model() {
            return model;
        }

        /** 对外展示的模型名：外部注入的客户端用类名表示 */
        public String modelName() {
            return llm == null ? model : "custom(" + llm.getClass().getSimpleName() + ")";
        }

        public int maxSteps() {
            return maxSteps;
        }

        public int maxSessions() {
            return maxSessions;
        }

        public boolean allowWrite() {
            return allowWrite;
        }

        public HttpAccessPolicy httpAccess() {
            return httpAccess;
        }

        public int httpTimeoutMs() {
            return httpTimeoutMs;
        }

        public int httpMaxResponseBytes() {
            return httpMaxResponseBytes;
        }

        public String system() {
            return system;
        }

        public LlmClient llm() {
            return llm;
        }

        public ApprovalGate approval() {
            return approval;
        }

        public WorkspaceInitializer initializer() {
            return initializer;
        }

        public List<Tool> tools() {
            return tools;
        }

        public boolean help() {
            return help;
        }

    }

}
