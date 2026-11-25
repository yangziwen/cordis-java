package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 本包共用的 Jackson 入口与参数校验（适配层才允许出现 JSON 依赖） */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** 取必填字符串参数；缺失或空白时给出模型能看懂的报错 */
    static String required(JsonNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull() || node.asText().trim().isEmpty()) {
            throw new IllegalArgumentException("missing \"" + name + "\"");
        }
        return node.asText().trim();
    }

}
