package cordis.agent.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@code http_get} / {@code http_post} 共用的执行体：策略校验 → 发请求 →
 * 手动跟随重定向 → 读数截断 → 塑形成模型能读的 JSON。
 *
 * <h3>为什么手动跟随重定向</h3>
 *
 * <p>{@link HttpAccessPolicy} 是按 URL 逐次判定的，而 OkHttp 的自动重定向在
 * 内部完成、不回调调用方——白名单里的地址 302 到内网就绕过了整个策略。所以
 * 这里用 {@code followRedirects(false)} 的 client，每跳显式解析
 * {@code Location}、再问一次策略。
 *
 * <h3>为什么同步阻塞</h3>
 *
 * <p>{@link cordis.agent.Tool#run} 的线程契约就是「在 worker 上同步执行、允许
 * 阻塞」，直接调 OkHttp 的同步 API 是正确用法；绕到 {@code Promise.join()} 反而
 * 要多等一次主循环调度（fs 工具调 {@code *Sync} 是同一个道理）。
 */
final class HttpCaller {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkHttpClient client;

    private final HttpAccessPolicy policy;

    private final int maxBytes;

    private final int maxRedirects;

    HttpCaller(OkHttpClient client, HttpAccessPolicy policy, int maxBytes, int maxRedirects) {
        this.client = client;
        this.policy = policy;
        this.maxBytes = maxBytes;
        this.maxRedirects = maxRedirects;
    }

    /**
     * 执行一次请求，返回给模型看的 JSON 文本。
     *
     * @throws SecurityException 目标不在策略白名单内（含重定向的中间跳）
     */
    String execute(String method, String rawUrl, Map<String, String> headers,
                   String body, String contentType) throws IOException {
        URI uri = parse(rawUrl);
        String currentUrl = uri.toString();
        String currentMethod = method;
        String currentBody = body;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            URI target = parse(currentUrl);
            if (!policy.allow(target)) {
                throw new SecurityException("http access denied: " + target
                        + " (host is not in the allowed list)");
            }
            Request request = request(currentMethod, target, headers, currentBody, contentType);
            try (Response response = client.newCall(request).execute()) {
                int status = response.code();
                if (isRedirect(status)) {
                    String location = response.header("Location");
                    if (location == null || location.trim().isEmpty()) {
                        return render(target, response, response.body());
                    }
                    if (status == 303) {
                        currentMethod = "GET";                      // 303 的语义就是「换个 GET 去拿」
                        currentBody = null;
                    }
                    currentUrl = target.resolve(location.trim()).toString();
                    continue;
                }
                return render(target, response, response.body());
            }
        }
        throw new IllegalStateException("too many redirects (max " + maxRedirects
                + "): " + rawUrl);
    }

    private static Request request(String method, URI target, Map<String, String> headers,
                                   String body, String contentType) {
        Request.Builder builder = new Request.Builder().url(target.toString());
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        RequestBody payload = payload(method, body, contentType);
        return builder.method(method, payload).build();
    }

    /** GET/HEAD 无 body；其余方法把 body 原样发出去，默认按 JSON 声明 */
    private static RequestBody payload(String method, String body, String contentType) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return null;
        }
        MediaType type = MediaType.parse(contentType == null || contentType.trim().isEmpty()
                ? "application/json; charset=utf-8" : contentType.trim());
        return RequestBody.create(type, body == null ? "" : body);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    /** 塑形：状态码、最终 URL、内容类型、字节数、是否截断/二进制、body */
    private String render(URI uri, Response response, ResponseBody body) throws IOException {
        byte[] bytes = new byte[0];
        boolean truncated = false;
        if (body != null) {
            Body read = readAtMost(body);
            bytes = read.bytes;
            truncated = read.truncated;
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("status", response.code());
        out.put("url", uri.toString());
        String contentType = response.header("Content-Type");
        if (contentType != null) {
            out.put("contentType", contentType);
        }
        out.put("bytes", bytes.length);
        out.put("truncated", truncated);
        boolean binary = containsNul(bytes);
        out.put("binary", binary);
        if (!binary) {
            out.put("body", new String(bytes, StandardCharsets.UTF_8));
        }
        return JSON.writeValueAsString(out);
    }

    private static Body readAtMost(ResponseBody body, int limit) throws IOException {
        InputStream in = body.byteStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        while (true) {
            int want = Math.min(buffer.length, limit + 1 - out.size());
            if (want <= 0) {
                break;
            }
            int read = in.read(buffer, 0, want);
            if (read <= 0) {
                break;
            }
            out.write(buffer, 0, read);
            if (out.size() > limit) {
                truncated = true;
                break;
            }
        }
        byte[] all = out.toByteArray();
        return new Body(truncated ? Arrays.copyOf(all, limit) : all, truncated);
    }

    private Body readAtMost(ResponseBody body) throws IOException {
        return readAtMost(body, maxBytes);
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    static URI parse(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("missing \"url\"");
        }
        try {
            return new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid url: " + url + " (" + e.getMessage() + ")");
        }
    }

    /** {@code "headers"} 参数：可选，只接受字符串值 */
    static Map<String, String> headers(JsonNode args) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        JsonNode node = args.path("headers");
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                headers.put(field.getKey(), field.getValue().asText(""));
            }
        }
        return headers;
    }

    private static final class Body {

        final byte[] bytes;

        final boolean truncated;

        Body(byte[] bytes, boolean truncated) {
            this.bytes = bytes;
            this.truncated = truncated;
        }

    }

}
