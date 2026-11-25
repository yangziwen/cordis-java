package cordis.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cordis.agent.Tool;

/**
 * {@code http_post}：发一次 POST（默认按 JSON 声明 Content-Type）。
 *
 * <p>没有单独提供 PUT/DELETE/PATCH：模型最常用的就是 GET 与 POST（查询与
 * 提交）。真要别的动词，等有第二个真实场景再加，不提前铺一排只有一个参数
 * 不同的工具——每个工具都会占用 system 里的 function 定义预算。
 */
final class HttpPostTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpCaller caller;

    HttpPostTool(HttpCaller caller) {
        this.caller = caller;
    }

    @Override
    public String name() {
        return "http_post";
    }

    @Override
    public String description() {
        return "对指定 URL 发起 HTTP POST 请求（默认 Content-Type: application/json），"
                + "返回状态码与响应体。只能访问被允许的主机。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\",\"description\":\"完整 URL，含 http/https 协议\"},"
                + "\"body\":{\"type\":\"string\",\"description\":\"请求体原文\"},"
                + "\"contentType\":{\"type\":\"string\",\"description\":"
                + "\"请求体类型，默认 application/json; charset=utf-8（可选）\"},"
                + "\"headers\":{\"type\":\"object\",\"description\":\"附加请求头（可选）\"}},"
                + "\"required\":[\"url\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JSON.readTree(arguments == null || arguments.trim().isEmpty()
                ? "{}" : arguments);
        String url = args.path("url").asText("");
        String body = args.path("body").asText("");
        String contentType = args.path("contentType").asText("");
        return caller.execute("POST", url, HttpCaller.headers(args), body,
                contentType.isEmpty() ? null : contentType);
    }

}
