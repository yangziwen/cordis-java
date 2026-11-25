package cordis.http;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import cordis.core.Deferred;
import cordis.core.Disposable;
import cordis.core.Promise;
import okhttp3.Call;

/**
 * 一次 SSE 订阅的句柄：既能等它结束，也能主动掐断。
 *
 * <p>这正是 {@link Promise} 单独用不了的场景——Promise 只表达"结果何时可用"，
 * 不表达"我要放弃这个结果"。两者分别由两个通道给出：
 *
 * <pre>
 *   SseCall call = http.stream(request, listener);
 *   call.completion().then(v -&gt; ...).catchError(e -&gt; ...);   // 何时结束
 *   call.dispose();                                          // 我可以不等了
 * </pre>
 *
 * <p>{@link #dispose()} 幂等，可在任意线程调用。它会取消底层 OkHttp Call
 * （关闭套接字、唤醒阻塞中的读），此后 listener 不再收到任何事件，
 * completion 以 {@link CancellationException} 失败——把"用户主动放弃"与
 * "网络故障"在类型上区分开，避免调用方把取消日志当成告警。
 */
public final class SseCall implements Disposable {

    private final Call call;

    private final Deferred<Void> completion;

    private final AtomicBoolean disposed = new AtomicBoolean();

    SseCall(Call call, Deferred<Void> completion) {
        this.call = call;
        this.completion = completion;
    }

    /** 流结束（正常读完 / 出错 / 被取消）时结算；值为 null */
    public Promise<Void> completion() {
        return completion.promise();
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        // cancel() 会中断正在 readUtf8Line 的线程；completion 只在这里 reject 取消原因
        call.cancel();
        completion.reject(new CancellationException("sse stream cancelled"));
    }

    /** 正常结束（读到流末尾）；已取消时忽略 */
    void completeOk() {
        if (!disposed.get()) {
            completion.resolve(null);
        }
    }

    /** 异常结束；已取消时忽略（取消本身已经结算过了） */
    void fail(Throwable error) {
        if (!disposed.get()) {
            completion.reject(error);
        }
    }

}
