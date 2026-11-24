package cordis.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * cordis-java 的一等公民 Promise（对应 TS 的 Promise / jQuery 的 deferred.promise）。
 *
 * 设计要点：
 * - <b>单线程完成语义</b>：所有状态迁移与回调都在 {@link Scheduler} 主循环上
 *   执行，与“逻辑单线程”调度模型一致——回调之间永无并发；
 * - <b>跨线程安全完成</b>：fulfill/reject 允许在任意线程调用（典型场景：
 *   扩展阻塞池里的 JDBC 查询完成时），内部自动投递回主循环，等价于
 *   Netty Promise 绑定 EventLoop 的行为；
 * - <b>Deferred 分离</b>：生产方持有 {@link Deferred}（resolve/reject），
 *   消费方只拿到本类实例（只读视图），谁都无法从外部篡改结果——
 *   这是 defer 模式的核心，也是 CompletableFuture 缺失的封装性。
 *
 * 本类只提供最小组合子（then / thenAsync / catchError / handle / all /
 * allSettled），语义与 JS Promise 一一对应；join() 是唯一的阻塞入口，
 * 仅供编排层与测试使用，框架内部从不调用。
 */
public class Promise<T> {

    public enum State { PENDING, FULFILLED, REJECTED }

    private final Scheduler scheduler;
    /** 仅供 join() 阻塞等待；状态迁移同样在此监视器下进行 */
    private final Object monitor = new Object();
    private State state = State.PENDING;
    private T value;
    private Throwable error;
    /** 待通知回调；只在主循环访问 */
    private List<Runnable> listeners;

    Promise(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** 创建已成功的 Promise */
    public static <T> Promise<T> resolved(Scheduler scheduler, T value) {
        Promise<T> p = new Promise<>(scheduler);
        p.fulfill(value);
        return p;
    }

    /** 创建已失败的 Promise */
    public static <T> Promise<T> rejected(Scheduler scheduler, Throwable error) {
        Promise<T> p = new Promise<>(scheduler);
        p.reject(error);
        return p;
    }

    // ---------- 状态迁移（Deferred 调用；任意线程可调，内部投递回主循环） ----------

    void fulfill(T value) {
        scheduler.execute(() -> complete(State.FULFILLED, value, null));
    }

    void reject(Throwable error) {
        scheduler.execute(() -> complete(State.REJECTED, null, error));
    }

    /** 状态迁移本体；必须运行在主循环（由 fulfill/reject 的 execute 保证） */
    private void complete(State s, T v, Throwable e) {
        Runnable[] toRun;
        synchronized (monitor) {
            if (state != State.PENDING) return;   // 幂等：首个完成生效，后续忽略
            state = s;
            value = v;
            error = e;
            monitor.notifyAll();                  // 唤醒 join()
            toRun = listeners == null ? null : listeners.toArray(new Runnable[0]);
            listeners = null;
        }
        // 回调在监视器外执行，避免用户回调与 join() 互相卡死
        if (toRun != null) {
            for (Runnable r : toRun) r.run();
        }
    }

    /** 注册 settled 回调；动作保证在主循环执行（已结算则内联立即执行） */
    private void onSettled(final Runnable action) {
        scheduler.execute(() -> {
            if (state != State.PENDING) {
                action.run();
            } else {
                if (listeners == null) listeners = new ArrayList<>();
                listeners.add(action);
            }
        });
    }

    // ---------- 组合 API ----------

    /**
     * 同步映射（对应 {@code then(v => mapper(v))}）。
     * mapper 在主循环执行，抛出异常则下游转为 rejected。
     */
    public <R> Promise<R> then(final Function<? super T, ? extends R> mapper) {
        final Promise<R> next = new Promise<>(scheduler);
        onSettled(() -> {
            if (state == State.FULFILLED) {
                try {
                    next.fulfill(mapper.apply(value));
                } catch (Throwable t) {
                    next.reject(t);
                }
            } else {
                next.reject(error);
            }
        });
        return next;
    }

    /**
     * 异步映射（对应 {@code then(v => anotherPromise)}）：mapper 返回新的
     * Promise，本链等它结算后才继续。这是串行编排的基本构件。
     */
    public <R> Promise<R> thenAsync(final Function<? super T, Promise<R>> mapper) {
        final Promise<R> next = new Promise<>(scheduler);
        onSettled(() -> {
            if (state == State.FULFILLED) {
                try {
                    Promise<R> inner = mapper.apply(value);
                    inner.onSettled(() -> {
                        if (inner.state == State.FULFILLED) {
                            next.fulfill(inner.value);
                        } else {
                            next.reject(inner.error);
                        }
                    });
                } catch (Throwable t) {
                    next.reject(t);
                }
            } else {
                next.reject(error);
            }
        });
        return next;
    }

    /** 异常恢复（对应 {@code catch}）：仅 REJECTED 时调用 recovery */
    public Promise<T> catchError(final Function<Throwable, ? extends T> recovery) {
        final Promise<T> next = new Promise<>(scheduler);
        onSettled(() -> {
            if (state == State.REJECTED) {
                try {
                    next.fulfill(recovery.apply(error));
                } catch (Throwable t) {
                    next.reject(t);
                }
            } else {
                next.fulfill(value);
            }
        });
        return next;
    }

    /**
     * 消费结果（成功或失败都会调用），并把原结果继续向后传递。
     * 适合旁路观察：日志、计数、调试。
     */
    public Promise<T> handle(final BiConsumer<? super T, ? super Throwable> consumer) {
        final Promise<T> next = new Promise<>(scheduler);
        onSettled(() -> {
            try {
                consumer.accept(value, error);
            } catch (Throwable t) {
                System.err.println("[cordis] promise handle callback failed: " + t);
            }
            if (state == State.REJECTED) next.reject(error);
            else next.fulfill(value);
        });
        return next;
    }

    // ---------- 组合子 ----------

    /**
     * 对应 {@code Promise.all}：全部成功则按顺序聚合结果；任一失败则整体
     * reject（首个失败原因）。
     */
    public static <T> Promise<List<T>> all(final Scheduler scheduler, final List<Promise<T>> promises) {
        final Deferred<List<T>> result = scheduler.defer();
        if (promises.isEmpty()) {
            result.resolve(new ArrayList<T>());
            return result.promise();
        }
        final List<T> values = new ArrayList<>(promises.size());
        for (int i = 0; i < promises.size(); i++) values.add(null);
        final int[] remaining = {promises.size()};
        for (int i = 0; i < promises.size(); i++) {
            final int index = i;
            final Promise<T> p = promises.get(index);
            p.onSettled(() -> {
                if (p.state == State.REJECTED) {
                    result.reject(p.error);
                    return;
                }
                values.set(index, p.value);
                if (--remaining[0] == 0) result.resolve(new ArrayList<T>(values));
            });
        }
        return result.promise();
    }

    /**
     * 对应 {@code Promise.allSettled}：等全部结算，无论成败，收集为
     * {@link Settled} 列表（parallel 的实现基础）。
     */
    public static <T> Promise<List<Settled<T>>> allSettled(final Scheduler scheduler,
                                                           final List<Promise<T>> promises) {
        final Deferred<List<Settled<T>>> result = scheduler.defer();
        if (promises.isEmpty()) {
            result.resolve(new ArrayList<Settled<T>>());
            return result.promise();
        }
        final List<Settled<T>> outcomes = new ArrayList<>(promises.size());
        for (int i = 0; i < promises.size(); i++) outcomes.add(null);
        final int[] remaining = {promises.size()};
        for (int i = 0; i < promises.size(); i++) {
            final int index = i;
            final Promise<T> p = promises.get(index);
            p.onSettled(() -> {
                outcomes.set(index, new Settled<T>(p.state == State.FULFILLED, p.value, p.error));
                if (--remaining[0] == 0) result.resolve(new ArrayList<Settled<T>>(outcomes));
            });
        }
        return result.promise();
    }

    // ---------- 阻塞入口（仅编排层 / 测试） ----------

    /**
     * 阻塞等待结果。<b>严禁在主循环线程调用</b>（会卡死全部调度），
     * 仅用于 main 编排脚本和测试的最终同步点。
     */
    public T join() {
        synchronized (monitor) {
            while (state == State.PENDING) {
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while joining promise", e);
                }
            }
        }
        if (state == State.FULFILLED) return value;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        throw new IllegalStateException("promise rejected", error);
    }

    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    @Override
    public String toString() {
        synchronized (monitor) {
            if (state == State.PENDING) return "Promise(PENDING)";
            if (state == State.FULFILLED) return "Promise(FULFILLED, " + value + ")";
            return "Promise(REJECTED, " + error + ")";
        }
    }

    /** allSettled 的单项结果（对应 {@code {status, value/reason}}） */
    public static final class Settled<T> {
        public final boolean success;
        public final T value;
        public final Throwable error;

        Settled(boolean success, T value, Throwable error) {
            this.success = success;
            this.value = value;
            this.error = error;
        }

        @Override
        public String toString() {
            return success ? "ok(" + value + ")" : "error(" + error + ")";
        }
    }
}
