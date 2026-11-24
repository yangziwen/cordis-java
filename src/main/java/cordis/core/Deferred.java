package cordis.core;

/**
 * 对应 JS 的 deferred：生产方持有的“可写句柄”。
 *
 * Promise/defer 模式的两半：
 * <pre>
 *   Deferred d = scheduler.defer();     // 生产方：谁负责算出结果，谁持有 d
 *   Promise p = d.promise();            // 消费方：只读视图，只管 then/catch
 *   d.resolve(value);                   // 任意线程可调，内部投递回主循环
 * </pre>
 *
 * 与直接暴露 CompletableFuture 的本质区别：resolve/reject 只在这里，
 * 消费方拿到的是无写接口的 {@link Promise}——结果不可能被外部篡改。
 *
 * @param <T> 结果类型
 */
public final class Deferred<T> {

    private final Promise<T> promise;

    Deferred(Scheduler scheduler) {
        this.promise = new Promise<>(scheduler);
    }

    /** 只读视图（对应 deferred.promise） */
    public Promise<T> promise() {
        return promise;
    }

    /** 完成成功（任意线程可调；幂等，首个生效） */
    public void resolve(T value) {
        promise.fulfill(value);
    }

    /** 完成失败（任意线程可调；幂等，首个生效） */
    public void reject(Throwable error) {
        promise.reject(error);
    }
}
