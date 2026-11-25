package cordis.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cordis.agent.Tool;

/**
 * {@code http_get}：发一次 GET，返回状态码与响应体。
 *
 * <p>HTTP 非 2xx 不算失败（与 OkHttp 语义一致）：404/500 也是"服务器给出的
 * 答案"，原样返回给模型，让它自己决定换 URL 还是换接口，而不是让 loop 把它
 * 当成框架错误。
 */
final class HttpGetTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpCaller caller;

    HttpGetTool(HttpCaller caller) {
        this.caller = caller;
    }

    @Override
    public String name() {
        return "http_get";
    }

    @Override
    public String description() {
        return "对指定 URL 发起 HTTP GET 请求，返回状态码、Content-Type 与响应体"
                + "（超过上限会截断，二进制内容只报字节数）。只能访问被允许的主机。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\",\"description\":\"完整 URL，含 http/https 协议\"},"
                + "\"headers\":{\"type\":\"object\",\"description\":"
                + "\"附加请求头，如 {\\\"Accept\\\":\\\"application/json\\\"}（可选）\"}},"
                + "\"required\":[\"url\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JSON.readTree(arguments == null || arguments.trim().isEmpty()
                ? "{}" : arguments);
        String url = args.path("url").asText("");
        return caller.execute("GET", url, HttpCaller.headers(args), null, null);
    }

}
