package cordis.agent;

/**
 * 流式 tool_calls 的一个增量分片（对应 OpenAI chunk 里
 * {@code delta.tool_calls[i]} 的可见字段）。
 *
 * <p>同一 id 的字段会分片到达：首个分片携带 {@code id} 与 {@code name}，
 * 后续分片通常只有 {@code argumentsFragment}——且片段本身<b>几乎必然是不
 * 完整的 JSON 文本</b>，比如先来 {@code {"ci"} 再来 {@code ty":"北京"}}。
 * 不到最后一个分片，任何解析尝试都会失败，这正是流式工具调用必须
 * "纯拼接、后解析"的原因。
 *
 * <p>本类不携带 wire 格式（JSON 解析交给把 SSE 转成 delta 的那一层），
 * 因此聚合器不依赖任何 JSON 库。
 */
public final class ToolCallDelta {

    /** 本次分片归属的调用槽位；同一次回复内从 0 递增 */
    public final int index;

    /** 调用 id；仅首个分片携带，其余为 null */
    public final String id;

    /** 工具名；仅首个分片携带，其余为 null */
    public final String name;

    /** 参数 JSON 的追加片段；可为 null */
    public final String argumentsFragment;

    public ToolCallDelta(int index, String id, String name, String argumentsFragment) {
        this.index = index;
        this.id = id;
        this.name = name;
        this.argumentsFragment = argumentsFragment;
    }

    @Override
    public String toString() {
        return "Delta(" + index + ", id=" + id + ", name=" + name
                + ", +" + (argumentsFragment == null ? 0 : argumentsFragment.length()) + " chars)";
    }

}
