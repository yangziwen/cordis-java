package cordis.agent;

/**
 * 一次 agent run 的结果。
 *
 * <p>为什么要一个对象而不是直接返回 {@code String}：loop 有两种结束方式，
 * 而这两种对调用方的含义完全不同——
 *
 * <ul>
 *   <li>{@link StopReason#ANSWERED}：模型不再请求工具，{@link #answer} 是终答；</li>
 *   <li>{@link StopReason#MAX_STEPS}：打满轮数上限，模型还想继续调工具，
 *       此时 {@link #answer} 通常是空串，任务<b>没有完成</b>。</li>
 * </ul>
 *
 * <p>早先的实现把后者拼成一句 {@code "(max steps reached: N)"} 当正常答案
 * 返回，调用方无法区分"答完了"和"放弃了"——这类哨兵字符串一旦进入历史或
 * 落库，就再也分不清它是模型说的还是框架说的。
 *
 * <p>不可变。
 */
public final class AgentResult {

    /** run 的结束原因 */
    public enum StopReason {

        /** 模型给出终答（没有再请求工具） */
        ANSWERED,

        /** 打满 maxSteps，模型仍在请求工具 */
        MAX_STEPS,

    }

    /** 终答文本；{@link StopReason#MAX_STEPS} 时通常为空串 */
    public final String answer;

    public final StopReason stopReason;

    /** 实际发生的模型调用轮数，从 1 开始 */
    public final int steps;

    /** 本轮 run 内一共执行了多少次工具调用（并行的一轮里可能有多个） */
    public final int toolCalls;

    public AgentResult(String answer, StopReason stopReason, int steps, int toolCalls) {
        this.answer = answer == null ? "" : answer;
        this.stopReason = stopReason;
        this.steps = steps;
        this.toolCalls = toolCalls;
    }

    /** 是否正常收尾（调用方最常用的那个判断） */
    public boolean completed() {
        return stopReason == StopReason.ANSWERED;
    }

    @Override
    public String toString() {
        return "AgentResult(" + stopReason + ", " + steps + " 轮, "
                + toolCalls + " 次工具, answer=" + answer + ")";
    }

}
