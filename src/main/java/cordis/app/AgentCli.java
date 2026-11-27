package cordis.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link AgentServer} 的控制台客户端：逐行输入，实时看到每一轮与每次工具
 * 调用，然后打印终答。零第三方 HTTP 依赖（{@code HttpURLConnection}）。
 *
 * <pre>
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli http://127.0.0.1:9000
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli --no-stream
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli --session my-job
 *   java -cp target/classes:$(cat /tmp/cp.txt) cordis.app.AgentCli --resume
 * </pre>
 *
 * <p>默认走 SSE：服务端每发生一个过程事件就推一帧，客户端立刻打印——长工具
 * 调用时不会盯着一片空白等；{@code --no-stream} 退回一次性 JSON。
 *
 * <p>会话 id 默认由本进程随机生成，并写入当前目录的 {@code .agent-cli-session}；
 * 下次 {@code --resume} 读回同一个 id 接上同一段历史。服务端历史持久化在
 * SQLite（{@code <root>/memory.db}），所以<b>服务端重启后同一 session id
 * 依然能接上上回的对话</b>；{@code --session} 则每次显式指定。
 *
 * <p>内置命令：{@code /help}、{@code /health}、{@code /history}、{@code /reset}、
 * {@code /exit}。
 */
public final class AgentCli {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;

    private final String session;

    /** 是否走 SSE；{@code --no-stream} 时为 false */
    private final boolean stream;

    private AgentCli(String baseUrl, String session, boolean stream) {
        this.baseUrl = baseUrl;
        this.session = session;
        this.stream = stream;
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = "http://" + AgentServer.DEFAULT_HOST + ":" + AgentServer.DEFAULT_PORT;
        String session = null;
        boolean stream = true;
        boolean resume = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--no-stream".equals(arg)) {
                stream = false;
            } else if ("--session".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("[agent-cli] --session needs a value");
                    System.exit(2);
                    return;
                }
                session = args[++i];
            } else if ("--resume".equals(arg)) {
                resume = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return;
            } else if (!arg.startsWith("--")) {
                baseUrl = arg;
            } else {
                System.err.println("[agent-cli] unknown option: " + arg);
                System.exit(2);
                return;
            }
        }
        boolean resumed = false;
        if (session == null || session.trim().isEmpty()) {
            if (resume) {
                String saved = readSessionFile();
                if (saved != null && !saved.isEmpty()) {
                    session = saved;
                    resumed = true;
                }
            }
            if (session == null || session.trim().isEmpty()) {
                session = "cli-" + Long.toHexString(System.nanoTime());
            }
        }
        AgentCli cli = new AgentCli(trimSlash(baseUrl), session.trim(), stream);
        saveSessionFile(cli.session);

        System.out.println("agent cli → " + cli.baseUrl + "  (session " + cli.session
                + (cli.stream ? ", stream" : ", json")
                + (resumed ? ", 续接上次历史" : "") + ")");
        System.out.println("/help 看命令，/exit 退出");
        System.out.println();

        BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("你> ");
            System.out.flush();
            String line = input.readLine();
            if (line == null) {
                System.out.println();
                return;                                        // Ctrl-D
            }
            String message = line.trim();
            if (message.isEmpty()) {
                continue;
            }
            if ("/exit".equals(message) || "/quit".equals(message)) {
                return;
            }
            if ("/help".equals(message)) {
                System.out.println("  /health  查看服务状态（模型、工具、技能数）");
                System.out.println("  /history 回放本会话的问答历史（用户问题与模型终答）");
                System.out.println("  /reset   清空本会话历史");
                System.out.println("  /exit    退出");
                System.out.println("  当前模式：" + (cli.stream
                        ? "SSE 流式（启动时加 --no-stream 可关闭）"
                        : "一次性 JSON（--no-stream）"));
                continue;
            }
            if ("/health".equals(message)) {
                System.out.println(pretty(cli.get("/health")));
                System.out.println();
                continue;
            }
            if ("/reset".equals(message)) {
                ObjectNode body = JSON.createObjectNode();
                body.put("session", cli.session);
                System.out.println(pretty(cli.post("/reset", body.toString())));
                System.out.println();
                continue;
            }
            if ("/history".equals(message)) {
                cli.history();
                continue;
            }
            cli.chat(message);
        }
    }

    private static void printUsage() {
        System.out.println("用法: cordis.app.AgentCli [服务地址] [选项]");
        System.out.println();
        System.out.println("  服务地址                 默认 http://" + AgentServer.DEFAULT_HOST
                + ":" + AgentServer.DEFAULT_PORT);
        System.out.println("  --session <id>           固定会话 id，默认本进程随机生成");
        System.out.println("  --resume                  续用上次保存的会话 id（服务端历史在"
                + " <root>/memory.db，服务端重启也能接上）");
        System.out.println("  --no-stream              一次性 JSON（默认走 SSE 流式）");
        System.out.println("  --help                   显示本帮助");
    }

    /** 当前目录的 .agent-cli-session 记着上次会话 id；读不到返回 null */
    private static String readSessionFile() {
        try {
            Path file = Paths.get(".agent-cli-session");
            if (Files.exists(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
            // 读不到就当没有
        }
        return null;
    }

    private static void saveSessionFile(String session) {
        try {
            Files.write(Paths.get(".agent-cli-session"),
                    session.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 只是个便利文件，写不进也不影响对话
        }
    }

    /** 一轮对话：默认走 SSE，过程事件逐帧打印 */
    private void chat(String message) {
        ObjectNode body = JSON.createObjectNode();
        body.put("session", session);
        body.put("message", message);
        if (!stream) {
            chatOnce(body);
            return;
        }
        body.put("stream", true);
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection connection = open("/chat", "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status >= 400) {
                System.out.println("[错误] HTTP " + status + " "
                        + new String(readAll(connection.getErrorStream()), StandardCharsets.UTF_8));
                System.out.println();
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            boolean sawFrame = false;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;                              // 空行只是帧分隔
                }
                sawFrame = true;
                printEvent(JSON.readTree(line.substring(6)), start);
            }
            if (!sawFrame) {
                System.out.println("[协议不符] server 没有返回 SSE 帧；"
                        + " server 版本过旧时用 --no-stream，或升级 server");
                System.out.println();
            }
        } catch (Exception e) {
            System.out.println("[连接失败] " + e.getMessage() + "（server 起了吗？）");
            System.out.println();
        }
    }

    /** SSE 帧：工具调用用 →/← 成对，终答单独一段 */
    private void printEvent(JsonNode event, long start) {
        String type = event.path("type").asText();
        if ("tool_start".equals(type)) {
            System.out.println("   → " + event.path("name").asText()
                    + " " + event.path("arguments").asText());
        } else if ("tool".equals(type)) {
            System.out.println("   ← " + event.path("name").asText()
                    + " " + event.path("result").asText());
        } else if ("final".equals(type)) {
            System.out.println();
            System.out.println(event.path("answer").asText());
            System.out.println();
            System.out.println("   " + event.path("stopReason").asText()
                    + "，" + event.path("steps").asInt() + " 轮，"
                    + event.path("toolCalls").asInt() + " 次工具，"
                    + (System.currentTimeMillis() - start) + "ms");
            System.out.println();
        } else if ("error".equals(type)) {
            System.out.println("[错误] " + event.path("message").asText());
            System.out.println();
        }
    }

    /**
     * /history：拉取服务端库里的问答主干（qa=1）逐条回放——
     * 也就是每轮发给模型的那份历史，工具过程不在其中。
     */
    private void history() {
        ObjectNode body = JSON.createObjectNode();
        body.put("session", session);
        try {
            JsonNode reply = post("/history", body.toString());
            int count = reply.path("count").asInt(0);
            System.out.println("会话 " + session + " 的问答历史，共 " + count + " 条：");
            int index = 0;
            for (JsonNode item : reply.path("messages")) {
                String prefix = "user".equals(item.path("role").asText()) ? ". 你: " : ". 答: ";
                System.out.println("  " + (++index) + prefix + item.path("content").asText());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println("[错误] " + e.getMessage()
                    + "（server 起了吗？旧版本没有 /history）");
            System.out.println();
        }
    }

    /** {@code --no-stream}：先打印过程事件，再打印终答 */
    private void chatOnce(ObjectNode body) {
        long start = System.currentTimeMillis();
        try {
            JsonNode reply = post("/chat", body.toString());
            if (reply.has("error")) {
                System.out.println("[错误] " + reply.get("error").asText());
                System.out.println();
                return;
            }
            JsonNode events = reply.get("events");
            if (events != null) {
                for (JsonNode event : events) {
                    if ("tool".equals(event.path("type").asText())) {
                        System.out.println("   [tool] " + event.path("name").asText()
                                + " -> " + event.path("result").asText());
                    }
                }
            }
            System.out.println();
            System.out.println(reply.path("answer").asText());
            System.out.println();
            System.out.println("   " + reply.path("stopReason").asText()
                    + "，" + reply.path("steps").asInt() + " 轮，"
                    + reply.path("toolCalls").asInt() + " 次工具，"
                    + (System.currentTimeMillis() - start) + "ms");
            System.out.println();
        } catch (Exception e) {
            System.out.println("[连接失败] " + e.getMessage() + "（server 起了吗？）");
            System.out.println();
        }
    }

    // ---------- HTTP ----------

    private JsonNode get(String path) throws Exception {
        return readResponse(open(path, "GET"));
    }

    private JsonNode post(String path, String json) throws Exception {
        HttpURLConnection connection = open(path, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(connection);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(300000);                     // 模型思考可能很久
        return connection;
    }

    private static JsonNode readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        String text = new String(readAll(in), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new IllegalStateException("HTTP " + status + ": " + text);
        }
        return JSON.readTree(text);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

    private static String pretty(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    private static String trimSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

}
