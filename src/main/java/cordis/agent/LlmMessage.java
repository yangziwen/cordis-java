package cordis.agent;

import java.util.Collections;
import java.util.List;

/**
 * 对话历史中的一条消息（OpenAI chat 消息的最小子集）。
 *
 * <p>三种角色：
 *
 * <ul>
 *   <li>{@code user} —— 用户输入；</li>
 *   <li>{@code assistant} —— 模型输出；可能是纯文本，也可能是工具调用
 *       （带 {@code toolCalls}，此时 {@code content} 常为空）；</li>
 *   <li>{@code tool} —— 某次工具调用的回填结果，用 {@code toolCallId} 与
 *       assistant 消息里的调用 id 配对。</li>
 * </ul>
 *
 * <p>不可变。AgentService 会在传入的 {@code history} 列表上追加，
 * 调用方持有这个列表即持有整段会话记忆——持久化（写入 SQLite）就是把
 * 这个列表落库，属于调用方的职责，不在本类内。
 */
public final class LlmMessage {

    /** "user" / "assistant" / "tool" */
    public final String role;

    /** 文本内容；assistant 纯工具调用轮可能为空串 */
    public final String content;

    /** assistant 消息携带的工具调用；其余角色为空列表 */
    public final List<ToolCall> toolCalls;

    /** tool 消息的配对 id；其余角色为 null */
    public final String toolCallId;

    private LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, Collections.<ToolCall>emptyList(), null);
    }

    /** 把模型的一轮回复固化为 assistant 消息（无论是否带工具调用） */
    public static LlmMessage assistant(LlmReply reply) {
        List<ToolCall> calls = reply.toolCalls.isEmpty()
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(reply.toolCalls);
        return new LlmMessage("assistant", reply.content, calls, null);
    }

    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage("tool", content, Collections.<ToolCall>emptyList(), toolCallId);
    }

    @Override
    public String toString() {
        if ("tool".equals(role)) {
            return role + "(" + toolCallId + "): " + content;
        }
        if (!toolCalls.isEmpty()) {
            return role + "(tools): " + toolCalls;
        }
        return role + ": " + content;
    }

}
