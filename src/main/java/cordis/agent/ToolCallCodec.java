package cordis.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ToolCall} 列表与单列文本之间的编解码——SQLite 没有 JSON 列，
 * 而 assistant 消息需要把工具调用原样存下来。
 *
 * <p>格式：每次调用一行，字段以 {@code \t} 分隔，字段内的反斜杠、制表符、
 * 换行、回车做反斜杠转义：
 *
 * <pre>
 *   call_1&#9;math&#9;{"a":6,"b":7}
 *   call_2&#9;clock&#9;{}
 * </pre>
 *
 * <p><b>边界</b>：这只是持久化编码，不是 wire 格式——发给模型的请求体仍在
 * {@link LlmClient} 实现里生成（那里需要真正的 JSON 序列化）。把两件事分开，
 * 是为了将来引入 JSON 库时只替换 wire 层，不影响已落库的数据。
 *
 * <p>选分隔符而非 JSON 的另一个理由：{@code arguments} 本身就是一段 JSON，
 * 若外层也用 JSON 包一层，就需要一个能正确处理嵌套与转义的解析器；而本框架
 * 刻意不引入 JSON 库。转义分隔符方案只有 4 个转义字符，可 100% 逆向。
 */
public final class ToolCallCodec {

    private ToolCallCodec() {
    }

    /** 编码；空列表返回 null（存 NULL 而不是空串，便于用 IS NULL 判断） */
    public static String encode(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCall call : calls) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            escape(sb, call.id);
            sb.append('\t');
            escape(sb, call.name);
            sb.append('\t');
            escape(sb, call.arguments);
        }
        return sb.toString();
    }

    /** 解码；null / 空串返回空列表 */
    public static List<ToolCall> decode(String text) {
        List<ToolCall> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return calls;
        }
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("corrupted tool_calls column: " + line);
            }
            calls.add(new ToolCall(unescape(parts[0]), unescape(parts[1]), unescape(parts[2])));
        }
        return calls;
    }

    private static void escape(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    sb.append(c);
            }
        }
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case 't':
                    sb.append('\t');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                default:
                    sb.append('\\').append(escaped);
            }
        }
        return sb.toString();
    }

}
