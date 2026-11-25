package cordis.agent.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.Tool;
import cordis.agent.ToolCall;
import cordis.agent.ToolCallAggregator;
import cordis.agent.ToolCallDelta;
import cordis.core.Disposable;
import cordis.http.SseEvent;
import cordis.http.SseParser;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * {@link LlmClient} 的 wire 层实现：对接任意 <b>OpenAI ChatCompletions 兼容</b>
 * 的 HTTP 服务。
 *
 * <p>所谓"任意"，指的是这一层对外只有三个可变量——{@code baseUrl}、
 * {@code apiKey}、{@code model}。公司与各家的模型被网关收口到同一个形状后，
 * 换模型只是换 {@code model} 字符串；换到 OpenAI 官方 / Azure / vLLM /
 * Ollama / DeepSeek 直连，换 {@code baseUrl} 与 {@code apiKey} 即可，
 * 代码一行不动。
 *
 * <h3>为什么用同步 execute，而不是 {@code HttpService.stream()}</h3>
 *
 * <p>{@link LlmClient#chat} 的契约本来就是"在 worker 上阻塞到本轮出结果"，
 * 所以这里直接 {@code call.execute()}：读 body 用当前这个 worker 线程，
 * 一条流只占一个线程。{@code HttpService.stream()} 面向的是另一类场景——
 * 事件要边到边推给上层（UI、日志），不能等。同一个 SSE 解析器
 * （{@link SseParser}）两条路径共用。
 *
 * <h3>流式链路</h3>
 *
 * <pre>
 *   byte stream → SseParser → SseEvent("data: {...}") → JsonNode
 *                   ├─ choices[0].delta.content        → 文本累积
 *                   └─ choices[0].delta.tool_calls[i]  → ToolCallDelta → ToolCallAggregator
 *                                                          → List&lt;ToolCall&gt;（流结束后才完整）
 * </pre>
 *
 * <p>{@code arguments} 在整个流期间都是半截 JSON，因此聚合器只做纯拼接，
 * 到 {@code [DONE]} 之后才交给 Jackson 校验一次；截断的流会在这里就报错，
 * 而不是把半截参数喂给工具、让错误在下一轮才浮现。
 *
 * <h3>网关的实测怪癖（都不影响本实现，但值得知道）</h3>
 *
 * <ul>
 *   <li>只接受 {@code system/user/assistant/tool} 四种角色，发 {@code developer}
 *       直接 400；</li>
 *   <li>响应里的 {@code model} 字段会被换成上游真名（请求 {@code gpt-4o-mini}
 *       可能回显 {@code gpt-6-luna-...}）——所以任何分支判断都不能看它；</li>
 *   <li>非流式响应里同时带 {@code delta: null} 和 {@code message}；流式块里
 *       额外带 {@code lastOne}，DeepSeek 系列带 {@code reasoning_content}；
 *       这些都是侧载字段，一律忽略。</li>
 * </ul>
 *
 * <p>线程安全：{@link #chat} 可被多个 worker 并发调用（每轮各自新建 Call）。
 * {@link #dispose()} 之后不再接受新请求。
 */
public final class OpenAiLlmClient implements LlmClient, Disposable {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** 流结束哨兵：网关在最后一条 data 里发它，随后关闭连接 */
    private static final String STREAM_DONE = "[DONE]";

    /** 输出被 max_tokens 截断——不是正常终答 */
    private static final String FINISH_LENGTH = "length";

    /** 输出被内容安全策略拦截 */
    private static final String FINISH_CONTENT_FILTER = "content_filter";

    /** 完整的 chat/completions 地址（baseUrl 已在构造时归一化） */
    private final String endpoint;

    private final String apiKey;

    private final String model;

    /** 可选的系统提示，为空则不下发 system 消息 */
    private final String systemPrompt;

    private final boolean stream;

    /** 0 表示不下发 max_tokens，交给模型默认值 */
    private final int maxTokens;

    private final OkHttpClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean disposed = new AtomicBoolean();

    public OpenAiLlmClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, null, true, 0);
    }

    /**
     * @param baseUrl      到 {@code /v1/openai/native} 或 {@code /v1} 一级即可，
     *                     本类负责拼上 {@code /chat/completions}
     * @param stream       true 走 SSE（首字延迟低、工具调用必须靠聚合器拼），
     *                     false 走一次性 JSON（实现简单、便于对拍）
     * @param systemPrompt 可为 null；非 null 时作为历史之前的 system 消息下发
     * @param maxTokens    0 表示不下发
     */
    public OpenAiLlmClient(String baseUrl, String apiKey, String model, String systemPrompt,
                           boolean stream, int maxTokens) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl must not be empty");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("model must not be empty");
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/chat/completions";
        this.apiKey = apiKey.trim();
        this.model = model.trim();
        this.systemPrompt = systemPrompt;
        this.stream = stream;
        this.maxTokens = maxTokens;
        // 流式必须 readTimeout(0)：模型思考期间连接上可能长时间一个字节都没有，
        // 任何非零读超时都会被误判成“连接断了”。代价是失去卡死保护——由 agent
        // 的取消（AgentCall.dispose）与进程退出兜底，这也是 Netty/OkHttp 对
        // 长连接的一贯取舍。
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(stream ? 0 : 300, TimeUnit.SECONDS)
                .build();
    }

    // ---------- LlmClient ----------

    @Override
    public LlmReply chat(List<LlmMessage> history, List<Tool> tools) throws Exception {
        if (disposed.get()) {
            throw new IllegalStateException("llm client has been disposed");
        }
        Request request = buildRequest(history, tools);
        Call call = client.newCall(request);
        Response response = call.execute();
        try (ResponseBody body = response.body()) {
            if (!response.isSuccessful()) {
                throw new IOException("llm request failed: HTTP " + response.code()
                        + " " + explainFailure(body));
            }
            if (body == null) {
                throw new IOException("llm response has no body");
            }
            return stream ? readStream(body) : readWhole(body);
        }
    }

    // ---------- 请求构造 ----------

    private Request buildRequest(List<LlmMessage> history, List<Tool> tools)
            throws JsonProcessingException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        for (LlmMessage message : history) {
            messages.add(messageNode(message));
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode declarations = payload.putArray("tools");
            for (Tool tool : tools) {
                declarations.add(toolNode(tool));
            }
        }
        payload.put("stream", stream);
        if (maxTokens > 0) {
            payload.put("max_tokens", maxTokens);
        }
        RequestBody body = RequestBody.create(JSON_MEDIA, mapper.writeValueAsString(payload));
        return new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .post(body)
                .build();
    }

    private ObjectNode messageNode(LlmMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role);
        if ("tool".equals(message.role)) {
            node.put("tool_call_id", message.toolCallId);
            node.put("content", message.content);
            return node;
        }
        if (message.toolCalls.isEmpty()) {
            node.put("content", message.content);
            return node;
        }
        // 带工具调用的 assistant 轮：content 按规范可为 null。空串也合法，
        // 但 null 更贴近各家模型训练时的形态。
        if (message.content.isEmpty()) {
            node.putNull("content");
        } else {
            node.put("content", message.content);
        }
        ArrayNode calls = node.putArray("tool_calls");
        for (ToolCall call : message.toolCalls) {
            if (call.id == null || call.id.isEmpty()) {
                throw new IllegalStateException("assistant tool call \"" + call.name
                        + "\" has no id; the following tool message could never be paired");
            }
            ObjectNode item = calls.addObject();
            item.put("id", call.id);
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", call.name);
            function.put("arguments", call.arguments);
        }
        return node;
    }

    private ObjectNode toolNode(Tool tool) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "function");
        ObjectNode function = node.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", parameters(tool));
        return node;
    }

    /** Tool 给的是 JSON <b>文本</b>，这里转成节点；坏 schema 在发请求前就炸掉 */
    private JsonNode parameters(Tool tool) {
        String schema = tool.parametersSchema();
        if (schema == null || schema.trim().isEmpty()) {
            ObjectNode empty = mapper.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            return empty;
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool \"" + tool.name()
                    + "\" parametersSchema is not valid JSON: " + schema, e);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException("tool \"" + tool.name()
                    + "\" parametersSchema must be a JSON object, got: " + schema);
        }
        return parsed;
    }

    // ---------- 非流式 ----------

    private LlmReply readWhole(ResponseBody body) throws IOException {
        JsonNode root = readJson(body.string(), "response body");
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            throw new IOException("llm response has no choices: " + abbreviate(root.toString()));
        }
        JsonNode choice = choices.get(0);
        requireFinishReason(text(choice.get("finish_reason")));
        JsonNode message = choice.path("message");
        String content = text(message.get("content"));
        JsonNode calls = message.get("tool_calls");
        if (calls == null || !calls.isArray() || calls.size() == 0) {
            return LlmReply.answer(content == null ? "" : content);
        }
        List<ToolCall> toolCalls = new ArrayList<>(calls.size());
        for (JsonNode call : calls) {
            ToolCall parsed = toolCall(call);
            requireParsable(parsed);
            toolCalls.add(parsed);
        }
        return LlmReply.of(content, toolCalls);
    }

    private ToolCall toolCall(JsonNode call) throws IOException {
        JsonNode function = call.path("function");
        String name = text(function.get("name"));
        if (name == null || name.isEmpty()) {
            throw new IOException("tool call without name: " + abbreviate(call.toString()));
        }
        return new ToolCall(text(call.get("id")), name, text(function.get("arguments")));
    }

    // ---------- 流式 ----------

    private LlmReply readStream(ResponseBody body) throws IOException {
        ToolCallAggregator aggregator = new ToolCallAggregator();
        StringBuilder content = new StringBuilder();
        SseParser parser = new SseParser();
        BufferedSource source = body.source();
        boolean done = false;
        String line;
        while (!done && (line = source.readUtf8Line()) != null) {
            SseEvent event = parser.accept(line);
            if (event == null) {
                continue;
            }
            done = consume(event, aggregator, content);
        }
        if (!done) {
            // 网关没发 [DONE] 就断流：把最后一条没以空行收尾的事件也吃掉
            SseEvent tail = parser.flush();
            if (tail != null) {
                consume(tail, aggregator, content);
            }
        }
        List<ToolCall> calls;
        try {
            calls = aggregator.complete();
        } catch (IllegalStateException e) {
            throw new IOException("llm stream ended with an incomplete tool call", e);
        }
        for (ToolCall call : calls) {
            requireParsable(call);
        }
        return LlmReply.of(content.toString(), calls);
    }

    /**
     * 消费一条 SSE 消息。
     *
     * @return true 表示收到 {@code [DONE]}，读循环可以停了
     */
    private boolean consume(SseEvent event, ToolCallAggregator aggregator, StringBuilder content)
            throws IOException {
        String data = event.data;
        if (data.trim().isEmpty()) {
            return false;
        }
        if (STREAM_DONE.equals(data.trim())) {
            return true;
        }
        JsonNode chunk = readJson(data, "sse chunk");
        JsonNode error = chunk.get("error");
        if (error != null && !error.isNull()) {
            // 有些网关在 200 的流里塞错误对象，而不是改状态码
            throw new IOException("llm stream error: " + abbreviate(error.toString()));
        }
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            return false;                       // 只携带 usage 的收尾块
        }
        JsonNode delta = choices.get(0).get("delta");
        requireFinishReason(text(choices.get(0).get("finish_reason")));
        if (delta == null || !delta.isObject()) {
            return false;
        }
        String fragment = text(delta.get("content"));
        if (fragment != null && !fragment.isEmpty()) {
            content.append(fragment);
        }
        JsonNode calls = delta.get("tool_calls");
        if (calls != null && calls.isArray()) {
            for (JsonNode call : calls) {
                aggregator.add(delta(call));
            }
        }
        return false;
    }

    /** {@code delta.tool_calls[i]} → 聚合器入参；缺失字段一律为 null，不补默认值 */
    private static ToolCallDelta delta(JsonNode call) {
        JsonNode function = call.get("function");
        boolean hasFunction = function != null && function.isObject();
        // index 是槽位号，OpenAI 流式必带；个别网关省略时退化为单槽位
        int index = call.has("index") ? call.get("index").asInt() : 0;
        return new ToolCallDelta(index,
                text(call.get("id")),
                hasFunction ? text(function.get("name")) : null,
                hasFunction ? text(function.get("arguments")) : null);
    }

    // ---------- 校验与工具方法 ----------

    /**
     * 参数必须是一段完整 JSON。聚合器能发现"缺 name"，但"参数只到一半"
     * 只有解析一次才看得见——截断的流如果放过去，工具会拿着半截 JSON 报一个
     * 与根因毫无关系的错。
     */
    private void requireParsable(ToolCall call) throws IOException {
        String arguments = call.arguments.trim();
        if (arguments.isEmpty()) {
            return;                             // 无参工具
        }
        try {
            mapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            throw new IOException("tool call \"" + call.name + "\" (" + call.id
                    + ") carries truncated arguments: " + abbreviate(arguments), e);
        }
    }

    /**
     * {@code finish_reason} 是唯一能识别"输出被截断"的信号：与 OpenAI 一致，
     * 长度打满时网关仍返回 200 + 半截内容。忽略它就会把半截答案当成终答
     * 交给调用方（实测 deepseek 系列会出现 {@code finish_reason=length}
     * 且 {@code content} 为空）。
     */
    private static void requireFinishReason(String finishReason) throws IOException {
        if (FINISH_LENGTH.equals(finishReason)) {
            throw new IOException("llm output truncated: finish_reason=length"
                    + " (raise max_tokens or shorten the history)");
        }
        if (FINISH_CONTENT_FILTER.equals(finishReason)) {
            throw new IOException("llm output blocked by content filter"
                    + " (finish_reason=content_filter)");
        }
    }

    private JsonNode readJson(String raw, String what) throws IOException {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IOException("malformed " + what + ": " + abbreviate(raw), e);
        }
        if (node == null) {
            throw new IOException("empty " + what);
        }
        return node;
    }

    /**
     * 把错误响应体翻成一句话。网关自己的 {@code message} 最准确
     * （例如"不支持的模型类型(model=xxx)"、"每分钟请求次数超过限制"），
     * 拿不到再退回原文。
     */
    private String explainFailure(ResponseBody body) {
        if (body == null) {
            return "<no body>";
        }
        String raw;
        try {
            raw = body.string();
        } catch (IOException e) {
            return "<unreadable body: " + e + ">";
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root != null) {
                String message = text(root.get("message"));
                if (message == null) {
                    message = text(root.path("error").get("message"));
                }
                if (message != null) {
                    return message;
                }
            }
        } catch (JsonProcessingException ignored) {
            // 非 JSON 错误页（网关异常时的 HTML），退回原文
        }
        return abbreviate(raw);
    }

    /** JsonNode → String；字段缺失与 JSON null 都映射成 null（不映射成 "null"） */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        String flat = value.replace('\n', ' ').trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    // ---------- 资源 ----------

    /**
     * 停派发 + 清空闲连接。同步调用不走 Dispatcher 线程池，所以这里主要是
     * 别把连接池和线程句柄留给 JVM 退出。
     */
    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Override
    public String toString() {
        return "OpenAiLlmClient(" + model + (stream ? ", stream" : ", whole") + ")";
    }

}
