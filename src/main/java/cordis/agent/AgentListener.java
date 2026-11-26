package cordis.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次 run 的私有观察者：事件直接回调给调用方，不走全局 {@code ctx.events}。
 *
 * <p>为什么需要它：{@code agent/step}、{@code agent/tool} 是<b>全局广播</b>，
 * 不带会话标识。一个进程里跑多个会话时，监听器只能看到"某个会话发生了某件事"，
 * 无法判断是谁的——上层的补救办法通常是加一把全局锁把所有 run 串起来，
 * 并发就没了。把监听器作为 run 的参数传进来，事件天然属于本次 run，
 * 多会话可以真并发（HTTP 层按会话推 SSE 就是靠这个）。
 *
 * <h3>线程契约</h3>
 *
 * <p>全部回调都在 {@code run} 所在的 worker 线程上<b>同步</b>执行，
 * 且严格按发生顺序：{@code onStep → onToolStart → onToolResult}（同一轮多个
 * 工具时，两个 onToolStart 先连发，onToolResult 仍按模型请求顺序回调）。
 *
 * <p>因此：
 * <ul>
 *   <li>回调里可以做轻量的阻塞（写 socket、写日志），但不能长时间阻塞——
 *       它占着 loop 的线程；</li>
 *   <li>回调里<b>不能</b>调用 {@code Promise.join}，也不该碰主循环上的状态
 *       （那需要 {@code scheduler.execute} 投递）；</li>
 *   <li>回调抛出的异常会被吞掉并打印到 stderr：观察者坏了不该毁掉对话。</li>
 * </ul>
 *
 * <p>所有方法都是 default，按需覆盖即可。{@link #NONE} 是"不观察"的常量。
 */
public interface AgentListener {

    /** 观察者自身的诊断日志（回调失败与对话无关，但必须留痕） */
    Logger LOG = LoggerFactory.getLogger(AgentListener.class);

    /** 不观察任何事件（{@code run(history, input)} 的默认行为） */
    AgentListener NONE = new AgentListener() {
    };

    /**
     * 每轮模型调用前。
     *
     * @param step         第几轮，从 1 开始
     * @param visibleTools 本轮可见工具数（热插拔会在轮与轮之间改变它）
     */
    default void onStep(int step, int visibleTools) {
    }

    /** 某个工具即将执行（同轮多个工具按模型请求顺序连续回调） */
    default void onToolStart(String name, String arguments) {
    }

    /**
     * 某个工具执行完毕，结果即将回填历史。
     *
     * @param result 工具返回的原文（失败时是 {@code {"error":"..."}} 形态）
     */
    default void onToolResult(String name, String result) {
    }

    /** 模型给出终答（本次 run 即将以 ANSWERED 结束） */
    default void onReply(String content) {
    }

    /** 回调失败统一降级为 WARN：观察者不该影响对话 */
    static void guard(String hook, Throwable error) {
        LOG.warn("listener.{} threw", hook, error);
    }

}
