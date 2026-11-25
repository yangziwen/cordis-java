package cordis.demo;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cordis.agent.AgentPlugin;
import cordis.agent.LlmClient;
import cordis.agent.LlmReply;
import cordis.agent.Tool;
import cordis.agent.ToolService;
import cordis.agent.http.HttpAccessPolicy;
import cordis.agent.http.HttpTools;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.http.HttpPlugin;
import cordis.util.Maps;

/**
 * {@code http_get} / {@code http_post} 的离线回归：起一个本地 HTTP 服务当靶子，
 * 直接调工具，不经过模型、不访问外网。
 *
 * <pre>
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.demo.HttpToolDemo
 * </pre>
 *
 * <p>覆盖的是「策略与协议」而不是网络本身：白名单命中/未命中、重定向逐跳
 * 校验（同 host 跟随、跨 host 拒绝）、响应截断、二进制识别、非 2xx 不算失败、
 * POST 回显，以及卸载后工具从注册表消失。
 */
public final class HttpToolDemo {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单次回填上限，故意设得很小，方便验证截断 */
    private static final int MAX_RESPONSE_BYTES = 1024;

    private HttpToolDemo() {
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        final HttpServer target = startTarget();
        final int port = target.getAddress().getPort();
        final String base = "http://127.0.0.1:" + port;

        final Context ctx = Context.root();
        final Fiber httpFiber = ctx.registry.use(HttpPlugin.INSTANCE, Maps.of(), null)
                .await().join();
        // 本 demo 直接调工具、不跑 loop；装 agent 只为拿到它提供的 agent.tools 服务
        final Fiber agentFiber = ctx.registry.use(AgentPlugin.INSTANCE,
                Maps.of("llm", unusedLlm()), null).await().join();
        final ToolService tools = ctx.get("agent.tools");
        final Fiber toolFiber = ctx.registry.use(HttpTools.INSTANCE,
                Maps.of("policy", HttpAccessPolicy.hosts("127.0.0.1:" + port),
                        "maxResponseBytes", MAX_RESPONSE_BYTES),
                Maps.of("http", true, "agent.tools", true)).await().join();

        try {
            failures += ok("hosts(\"*\") 放行任意主机并标记全开", () -> {
                HttpAccessPolicy open = HttpAccessPolicy.hosts("*");
                return open.open()
                        && open.allow(URI.create("http://any.example.invalid/x"))
                        && open.allow(URI.create("https://another.example.invalid:8443/y"))
                        && !open.allow(URI.create("file:///etc/passwd"));
            });

            failures += ok("hosts(\"*:8080\") 只放行该端口，不算全开", () -> {
                HttpAccessPolicy policy = HttpAccessPolicy.hosts("*:8080");
                return !policy.open()
                        && policy.allow(URI.create("http://a.example.invalid:8080/x"))
                        && !policy.allow(URI.create("http://a.example.invalid/x"));
            });

            failures += ok("装配后能看到 http_get / http_post",
                    () -> hasTool(tools, "http_get") && hasTool(tools, "http_post"));

            Tool get = tool(tools, "http_get");
            Tool post = tool(tools, "http_post");

            failures += ok("GET 200 返回状态码与 body", () -> {
                JsonNode result = parse(get.run("{\"url\":\"" + base + "/ok\"}"));
                return result.path("status").asInt() == 200
                        && result.path("body").asText().contains("\"ok\":true")
                        && !result.path("truncated").asBoolean()
                        && !result.path("binary").asBoolean();
            });

            failures += ok("非 2xx 不算失败，状态码原样回填", () -> {
                JsonNode result = parse(get.run("{\"url\":\"" + base + "/notfound\"}"));
                return result.path("status").asInt() == 404
                        && result.path("body").asText().contains("nope");
            });

            failures += ok("白名单外的主机被拒绝", () -> {
                String error = failure(() -> get.run(
                        "{\"url\":\"http://localhost:" + port + "/ok\"}"));
                return error.contains("access denied") && error.contains("localhost");
            });

            failures += ok("同 host 重定向被跟随，最终 URL 是 /ok", () -> {
                JsonNode result = parse(get.run("{\"url\":\"" + base + "/redirect-same\"}"));
                return result.path("status").asInt() == 200
                        && result.path("url").asText().endsWith("/ok")
                        && result.path("body").asText().contains("\"ok\":true");
            });

            failures += ok("跨 host 重定向被逐跳拦下（策略不被绕过）", () -> {
                String error = failure(() -> get.run(
                        "{\"url\":\"" + base + "/redirect-external\"}"));
                return error.contains("access denied")
                        && error.contains("example.invalid");
            });

            failures += ok("超长响应截断到上限", () -> {
                JsonNode result = parse(get.run("{\"url\":\"" + base + "/big\"}"));
                return result.path("status").asInt() == 200
                        && result.path("truncated").asBoolean()
                        && result.path("bytes").asInt() == MAX_RESPONSE_BYTES;
            });

            failures += ok("二进制响应只报字节数，不回填乱码", () -> {
                JsonNode result = parse(get.run("{\"url\":\"" + base + "/bin\"}"));
                return result.path("status").asInt() == 200
                        && result.path("binary").asBoolean()
                        && result.path("bytes").asInt() == 3
                        && !result.has("body");
            });

            failures += ok("POST 把 body 原样发出（服务端回显）", () -> {
                JsonNode result = parse(post.run("{\"url\":\"" + base + "/echo\","
                        + "\"body\":\"{\\\"hello\\\":\\\"world\\\"}\"}"));
                return result.path("status").asInt() == 200
                        && result.path("body").asText().contains("POST")
                        && result.path("body").asText().contains("hello");
            });

            failures += ok("参数缺 url 时给出可读错误", () -> {
                String error = failure(() -> get.run("{}"));
                return error.contains("missing") && error.contains("url");
            });

            failures += ok("卸载后工具从注册表消失（热插拔）", () -> {
                toolFiber.dispose();
                ctx.scheduler.awaitIdle();
                return !hasTool(tools, "http_get");
            });
        } finally {
            target.stop(0);
            toolFiber.dispose();                     // dispose 幂等，前面已卸过也无妨
            agentFiber.dispose();
            httpFiber.dispose();
            ctx.scheduler.awaitIdle();
            ctx.scheduler.shutdown();
        }

        System.out.println(failures == 0 ? "[http-tool-demo] PASS"
                : "[http-tool-demo] FAIL " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 只为满足 AgentPlugin 的装配要求：不跑 loop 就不会被调用 */
    private static LlmClient unusedLlm() {
        return (history, available, system) -> LlmReply.answer("unused");
    }

    // ---------- 靶子服务 ----------

    private static HttpServer startTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> reply(exchange, 200,
                "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/notfound", exchange -> reply(exchange, 404,
                "application/json; charset=utf-8", "{\"error\":\"nope\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/big", exchange -> {
            StringBuilder body = new StringBuilder();
            while (body.length() < 4000) {
                body.append("0123456789");
            }
            reply(exchange, 200, "text/plain; charset=utf-8",
                    body.toString().getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/bin", exchange -> reply(exchange, 200,
                "application/octet-stream", new byte[]{0, 1, 2}));
        server.createContext("/redirect-same", exchange -> {
            exchange.getResponseHeaders().set("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-external", exchange -> {
            // 一个必然解析失败的主机：只要策略生效，请求根本不会发出去
            exchange.getResponseHeaders().set("Location", "http://example.invalid/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/echo", exchange -> {
            String body = new String(readAll(exchange), StandardCharsets.UTF_8);
            reply(exchange, 200, "text/plain; charset=utf-8",
                    (exchange.getRequestMethod() + " " + body).getBytes(StandardCharsets.UTF_8));
        });
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "http-tool-demo");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, int status, String contentType, byte[] body)
            throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] readAll(HttpExchange exchange) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ---------- 断言辅助 ----------

    private interface Check {

        boolean run() throws Exception;

    }

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

    private static Tool tool(ToolService tools, String name) {
        for (Tool tool : tools.snapshot()) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        throw new IllegalStateException("tool not registered: " + name);
    }

    /** 解析工具返回的 JSON；不是 JSON 说明契约被破坏，直接失败 */
    private static JsonNode parse(String text) throws Exception {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("not json: " + text, e);
        }
    }

    /** 跑一次并拿到异常消息链：期望失败的用例用它（真的没抛异常时返回可识别的文本） */
    private static String failure(Call call) throws Exception {
        try {
            return "expected failure but got: " + call.run();
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

    private static boolean hasTool(ToolService tools, String name) {
        for (Tool tool : tools.snapshot()) {
            if (tool.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

}
