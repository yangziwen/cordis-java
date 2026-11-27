package cordis.agent;

/**
 * 一次模型调用的历史文本统计（观测口径，单位均为字符）。
 *
 * <p>统计位置在 {@code AgentService} 的调用点上：对发给模型的
 * {@code history} 逐条累加 {@code content} 长度——这是<b>原文</b>，
 * JSON 转义发生在 wire 层之后，不影响这里的数字。不含工具声明 JSON
 * （{@code tools} 字段）与消息结构的序列化开销。
 *
 * <dl>
 *   <dt>systemChars</dt><dd>system 指令文本（技能清单等动态段，每轮重发）；
 *       单独报出——它常比记忆本身还大，混在一起会掩盖记忆的真实增长</dd>
 *   <dt>userChars / assistantChars / toolChars</dt><dd>按 role 分桶的 history
 *       文本量。tool 桶即「本轮工具结果堆积」，user+assistant 桶即
 *       「问答主干」（qa 裁剪后跨轮的记忆就是这个）</dd>
 *   <dt>totalChars</dt><dd>三个桶之和，即模型本轮实际读到的全部历史文本</dd>
 *   <dt>messages</dt><dd>history 条数（不含 system）</dd>
 * </dl>
 *
 * <p>不可变。由 {@code AgentService} 在每次 {@code llm.chat} 前构建，经
 * {@link AgentListener#onStats} 回调（debug 模式）。
 */
public final class HistoryStats {

    public final int systemChars;
    public final int userChars;
    public final int assistantChars;
    public final int toolChars;
    public final int totalChars;
    public final int messages;

    public HistoryStats(int systemChars, int userChars, int assistantChars,
                       int toolChars, int totalChars, int messages) {
        this.systemChars = systemChars;
        this.userChars = userChars;
        this.assistantChars = assistantChars;
        this.toolChars = toolChars;
        this.totalChars = totalChars;
        this.messages = messages;
    }

    @Override
    public String toString() {
        return "HistoryStats(system=" + systemChars + ", history=" + totalChars
                + " [user " + userChars + " / assistant " + assistantChars
                + " / tool " + toolChars + "], messages=" + messages + ")";
    }

}
