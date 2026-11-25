package cordis.demo;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cordis.agent.AgentPlugin;
import cordis.agent.LlmClient;
import cordis.agent.LlmReply;
import cordis.agent.Tool;
import cordis.agent.ToolService;
import cordis.agent.browser.BrowserTools;
import cordis.browser.BrowserPlugin;
import cordis.browser.BrowserService;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.http.HttpAccessPolicy;
import cordis.util.Maps;

/**
 * 无头浏览器工具的离线回归：本地起一个「必须跑 JS 才有结果」的页面当靶子，
 * 走完整的 open → 取 ref → type → click → 读结果 链路，不访问外网。
 *
 * <pre>
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.demo.BrowserToolDemo
 * </pre>
 *
 * <p>需要本机有 Chrome（默认 {@code channel=chrome}）或已装 Playwright 浏览器。
 * 首次运行要拉起 node driver + Chromium，约 15 秒。
 */
public final class BrowserToolDemo {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试专用 UA：让页面能区分「自动化」以外的正常渲染 */
    private static final String PAGE_SEARCH = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<title>天气查询</title></head><body>"
            + "<h1>天气查询</h1>"
            + "<label for='city'>城市</label>"
            + "<input id='city' name='city' placeholder='输入城市'>"
            + "<button id='go' onclick=\"document.getElementById('out').textContent = "
            + "document.getElementById('city').value + ' 晴 12~24C'\">查询</button>"
            + "<div id='out'>--</div>"
            + "<ul><li><a href='/next'>下一页</a></li></ul>"
            + "</body></html>";

    private static final String PAGE_NEXT = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<title>第二页</title></head><body><h1>第二页</h1>"
            + "<a href='/search'>返回</a></body></html>";

    private static final String PAGE_SLOW = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<title>慢加载</title></head><body><h1>慢加载</h1>"
            + "<div id='out'>加载中</div>"
            + "<script>setTimeout(function(){document.getElementById('out').textContent"
            + " = '加载完成';}, 1200);</script></body></html>";

    private BrowserToolDemo() {
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        final HttpServer target = startTarget();
        final int port = target.getAddress().getPort();
        final String base = "http://127.0.0.1:" + port;
        final Path shots = Paths.get(System.getProperty("java.io.tmpdir"), "cordis-browser-demo");

        final Context ctx = Context.root();
        final Fiber agentFiber = ctx.registry.use(AgentPlugin.INSTANCE,
                Maps.of("llm", unusedLlm()), null).await().join();
        final Fiber browserFiber = ctx.registry.use(BrowserPlugin.INSTANCE,
                Maps.of("policy", HttpAccessPolicy.hosts("127.0.0.1:" + port),
                        "channel", "chrome",
                        "screenshotDir", shots.toString(),
                        "snapshotChars", 8000),
                null).await().join();
        final Fiber toolsFiber = ctx.registry.use(BrowserTools.INSTANCE, Maps.of(),
                Maps.of("browser", true, "agent.tools", true)).await().join();

        try {
            final ToolService tools = ctx.get("agent.tools");
            final BrowserService browser = ctx.get("browser");

            failures += ok("装配后能看到 8 个 browser_* 工具", () -> {
                int count = 0;
                for (Tool tool : tools.snapshot()) {
                    if (tool.name().startsWith("browser_")) {
                        count++;
                    }
                }
                return count == 8;
            });

            Tool open = tool(tools, "browser_open");
            Tool snapshot = tool(tools, "browser_snapshot");
            Tool click = tool(tools, "browser_click");
            Tool type = tool(tools, "browser_type");
            Tool wait = tool(tools, "browser_wait");
            Tool screenshot = tool(tools, "browser_screenshot");
            Tool close = tool(tools, "browser_close");

            // ---- 打开：拿到带 ref 的无障碍树 ----
            JsonNode opened = call(open, new ObjectNode(JSON.getNodeFactory())
                    .put("url", base + "/search").put("waitMs", 300));
            final String pageId = opened.path("pageId").asText();
            failures += ok("打开页面拿到标题与带 ref 的快照", () ->
                    opened.path("title").asText().contains("天气")
                            && opened.path("url").asText().endsWith("/search")
                            && opened.path("snapshot").asText().contains("[ref=")
                            && !pageId.isEmpty());

            final String textboxRef = ref(opened.path("snapshot").asText(), "textbox");
            final String buttonRef = ref(opened.path("snapshot").asText(), "button");
            failures += ok("从快照里解析出输入框与按钮的 ref（无需 CSS 选择器）", () ->
                    textboxRef != null && buttonRef != null);

            // ---- 输入 + 点击：结果只可能由页面 JS 写出来 ----
            call(type, new ObjectNode(JSON.getNodeFactory())
                    .put("pageId", pageId).put("ref", textboxRef).put("text", "北京"));
            JsonNode afterClick = call(click, new ObjectNode(JSON.getNodeFactory())
                    .put("pageId", pageId).put("ref", buttonRef));
            failures += ok("type + click 之后读到 JS 写出来的结果", () ->
                    afterClick.path("snapshot").asText().contains("北京 晴 12~24C"));

            // ---- 截图 ----
            JsonNode shot = call(screenshot, new ObjectNode(JSON.getNodeFactory())
                    .put("pageId", pageId));
            final Path shotFile = Paths.get(shot.path("path").asText());
            failures += ok("截图落盘且不是空文件", () ->
                    Files.size(shotFile) > 2000);

            // ---- 慢加载：wait 之后才看得到 ----
            JsonNode slow = call(open, new ObjectNode(JSON.getNodeFactory())
                    .put("url", base + "/slow").put("waitMs", 100));
            final String slowId = slow.path("pageId").asText();
            failures += ok("首次快照里还没有异步内容", () ->
                    !slow.path("snapshot").asText().contains("加载完成")
                            && !slow.path("text").asText().contains("加载完成"));
            JsonNode afterWait = call(wait, new ObjectNode(JSON.getNodeFactory())
                    .put("pageId", slowId).put("ms", 1600));
            failures += ok("browser_wait 之后拿到异步内容", () ->
                    afterWait.path("snapshot").asText().contains("加载完成")
                            || afterWait.path("text").asText().contains("加载完成"));

            // ---- 策略 ----
            failures += ok("file:// 被拒绝（能直接读本机文件）", () -> {
                String error = failure(open, new ObjectNode(JSON.getNodeFactory())
                        .put("url", "file:///etc/passwd"));
                return error.contains("only opens http/https");
            });
            failures += ok("白名单外的主机被拒绝", () -> {
                String error = failure(open, new ObjectNode(JSON.getNodeFactory())
                        .put("url", "http://localhost:" + port + "/search"));
                return error.contains("navigation denied") && error.contains("localhost");
            });

            // ---- 多页隔离与关闭 ----
            final int before = browser.pageCount();
            JsonNode next = call(open, new ObjectNode(JSON.getNodeFactory())
                    .put("url", base + "/next").put("waitMs", 200));
            final String nextId = next.path("pageId").asText();
            failures += ok("新页面单独占一个句柄（不串到旧页面）", () ->
                    !nextId.equals(pageId)
                            && !nextId.equals(slowId)
                            && browser.pageCount() == before + 1
                            && next.path("title").asText().contains("第二页"));
            failures += ok("旧页面的 JS 状态仍然保留", () ->
                    call(snapshot, new ObjectNode(JSON.getNodeFactory())
                            .put("pageId", pageId)).path("snapshot").asText()
                            .contains("北京 晴 12~24C"));

            JsonNode closed = call(close, new ObjectNode(JSON.getNodeFactory())
                    .put("pageId", nextId));
            failures += ok("close 之后页数回落到原值", () ->
                    closed.path("closed").asBoolean()
                            && browser.pageCount() == before);
            failures += ok("对已关页面再操作报 unknown page", () ->
                    failure(snapshot, new ObjectNode(JSON.getNodeFactory())
                            .put("pageId", nextId)).contains("unknown page"));
        } finally {
            target.stop(0);
            toolsFiber.dispose();
            browserFiber.dispose();
            agentFiber.dispose();
            ctx.scheduler.awaitIdle();
            ctx.scheduler.shutdown();
        }

        System.out.println(failures == 0 ? "[browser-tool-demo] PASS"
                : "[browser-tool-demo] FAIL " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- 靶子服务 ----------

    private static HttpServer startTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> html(exchange, PAGE_SEARCH));
        server.createContext("/next", exchange -> html(exchange, PAGE_NEXT));
        server.createContext("/slow", exchange -> html(exchange, PAGE_SLOW));
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "browser-tool-demo");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    private static void html(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 只为满足 AgentPlugin 的装配要求：本 demo 直接调工具，不跑 loop */
    private static LlmClient unusedLlm() {
        return (history, available, system) -> LlmReply.answer("unused");
    }

    // ---------- 断言辅助 ----------

    private interface Call {

        String run() throws Exception;

    }

    private static int ok(String name, Check check) {
        try {
            boolean passed = check.run();
            System.out.println("   " + (passed ? "[PASS]" : "[FAIL]") + " " + name);
            return passed ? 0 : 1;
        } catch (Throwable t) {
            System.out.println("   [FAIL] " + name + " → " + t);
            return 1;
        }
    }

    private interface Check {

        boolean run() throws Exception;

    }

    private static JsonNode call(Tool tool, ObjectNode args) throws Exception {
        return JSON.readTree(tool.run(JSON.writeValueAsString(args)));
    }

    /** 跑一次并拿到异常消息链：期望失败的用例用它 */
    private static String failure(Tool tool, ObjectNode args) {
        try {
            return "expected failure but got: "
                    + JSON.readTree(tool.run(JSON.writeValueAsString(args)));
        } catch (Throwable t) {
            StringBuilder messages = new StringBuilder();
            for (Throwable current = t; current != null; current = current.getCause()) {
                if (messages.length() > 0) {
                    messages.append(" <- ");
                }
                messages.append(current.getMessage());
            }
            return messages.toString();
        }
    }

    private static Tool tool(ToolService tools, String name) {
        for (Tool tool : tools.snapshot()) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        throw new IllegalStateException("tool not registered: " + name);
    }

    /** 从无障碍树里取某一类节点的 ref（形如 {@code - textbox "城市" [ref=e4]}） */
    private static String ref(String snapshot, String role) {
        for (String line : snapshot.split("\n")) {
            if (line.contains(" " + role) || line.contains(": " + role)) {
                int at = line.indexOf("[ref=");
                if (at >= 0) {
                    int end = line.indexOf(']', at);
                    if (end > at) {
                        return line.substring(at + 5, end);
                    }
                }
            }
        }
        return null;
    }

}
