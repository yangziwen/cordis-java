package cordis.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型的一轮输出：文本、工具调用，或两者兼有。
 *
 * <p>{@code toolCalls} 为空表示模型认为对话可以收尾，agent loop 以
 * {@code content} 作为最终回答；非空则逐个执行工具、回填结果、进入下一轮。
 */
public final class LlmReply {

    /** 文本部分；纯工具调用轮为空串 */
    public final String content;

    /** 本轮请求的工具调用；空列表表示终答 */
    public final List<ToolCall> toolCalls;

    private LlmReply(String content, List<ToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(toolCalls);
    }

    /** 纯文本终答 */
    public static LlmReply answer(String content) {
        return new LlmReply(content, null);
    }

    public static LlmReply withToolCalls(List<ToolCall> toolCalls) {
        return new LlmReply("", toolCalls);
    }

    /** 单个工具调用的便捷构造（脚手架 / 测试最常用） */
    public static LlmReply toolCall(String id, String name, String arguments) {
        List<ToolCall> calls = new ArrayList<>();
        calls.add(new ToolCall(id, name, arguments));
        return new LlmReply("", calls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        if (toolCalls.isEmpty()) {
            return "LlmReply(text: " + content + ")";
        }
        return "LlmReply(" + toolCalls + (content.isEmpty() ? "" : ", text: " + content) + ")";
    }

}
