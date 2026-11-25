package cordis.agent;

/**
 * 一次模型发起的工具调用（非流式视角下的完整形态）。
 *
 * <p>{@code id} 是模型的调用凭证：assistant 消息里声明"我要以 call_1 调用
 * math"，随后的 tool 消息必须以 call_1 回填结果，两边靠它对上。OpenAI 形如
 * {@code "call_xxx"}，Anthropic 用序号——本类不解释内容，只透传。
 *
 * <p>{@code arguments} 保持 JSON 字符串而不解析成结构：工具的实现方最清楚
 * 自己的 schema，由它自己解析。框架层与 agent 包因此不依赖任何 JSON 库，
 * 只有 wire 层（{@code cordis.agent.llm}）需要。
 */
public final class ToolCall {

    /** 调用 id，回填 tool 结果时用于配对 */
    public final String id;

    /** 工具名 */
    public final String name;

    /** 完整的参数 JSON 文本 */
    public final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments == null ? "" : arguments;
    }

    @Override
    public String toString() {
        return "ToolCall(" + id + ", " + name + ", " + arguments + ")";
    }

}
