package cordis.agent;

/**
 * 工具失败的<b>回填形状</b>：把"这次调用为什么没成"变成一段模型能读的 JSON。
 *
 * <p>为什么需要专门的一个类：agent loop 只在一处需要生成 JSON——工具失败时把
 * 原因回填给模型（{@code {"error": "..."}}）。为此引入完整的 JSON 库不划算，
 * 而手拼字符串不转义会产出非法 JSON——异常消息里带一个引号或换行就够把模型
 * 带偏，甚至让网关拒掉整段历史。本类只负责这一件事，{@link #quote} 是它的
 * 实现细节。
 *
 * <p>为什么不做成通用的 {@code JsonUtil}：通用 JSON 的读写发生在两端——wire
 * 层（{@code cordis.agent.llm}，用 Jackson 读写协议）和工具实现方（自己解析
 * 自己的 schema）；loop 层按设计只把 arguments 当成不透明文本。一个通用的
 * 文本拼接工具放出去，等于邀请别人绕过 wire 层手拼请求体。等真的有第二个包
 * 需要它，再按职责提公共也不迟。
 *
 * <p>本类不是 {@code cordis.core} 的通用组件，只在 agent 包内使用。
 */
final class ToolError {

    private ToolError() {
    }

    /** 工具失败的标准回填文本；null 消息会变成 JSON 的 {@code null} 字面量 */
    static String of(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    /** 把任意文本变成 JSON 字符串字面量（含两侧引号）；null 变成 {@code null} */
    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        // JSON 规范不允许裸控制字符，一律转成 unicode 转义（u + 四位十六进制）
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

}
