package cordis.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import cordis.core.Deferred;
import cordis.core.Disposable;
import cordis.core.Promise;

/**
 * 一次 agent run 的句柄：结构仿照 {@code cordis.http.SseCall}——事件走推送
 * 通道（events），结束走 completion，中途可取消。
 *
 * <pre>
 *   AgentCall call = agent.run(history, "现在几点了？");
 *   call.completion().then(answer -&gt; ...).catchError(e -&gt; ...);
 *   call.dispose();                     // 不等了
 * </pre>
 *
 * <p><b>取消粒度是"轮"不是"毫秒"</b>：dispose 后，loop 会在当前 LLM 调用 /
 * 工具执行结束后停止，不再开始下一轮。正在阻塞中的模型调用不会被中断——
 * 那需要流式读循环配合检查（接入真实 SSE 客户端时在 readUtf8Line 循环里
 * 判 {@link #isCancelled} 即可，当前同步抽象做不到）。
 *
 * <p>dispose 幂等，任意线程可调；取消后 completion 以
 * {@link CancellationException} 失败，与网络故障在类型上可区分。
 */
public final class AgentCall implements Disposable {

    private final Deferred<String> completion;

    private final AtomicBoolean cancelled = new AtomicBoolean();

    AgentCall(Deferred<String> completion) {
        this.completion = completion;
    }

    /** run 结束（终答 / 失败 / 被取消）时结算，值为最终回答文本 */
    public Promise<String> completion() {
        return completion.promise();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void dispose() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        completion.reject(new CancellationException("agent run cancelled"));
    }

    /** 终答；已取消时忽略 */
    void complete(String answer) {
        if (!cancelled.get()) {
            completion.resolve(answer);
        }
    }

    /** 失败；已取消时忽略 */
    void fail(Throwable error) {
        if (!cancelled.get()) {
            completion.reject(error);
        }
    }

}
