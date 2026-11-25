package cordis.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 packages/core/src/events.ts（异步化版本）。
 *
 * cordis 的事件系统比普通 EventEmitter 强得多：
 * - 同一个事件名支持 5 种派发模式（emit / parallel / serial / bail / waterfall）；
 * - 监听器以 effect 的形式挂在“注册方所在的 fiber”上，fiber 卸载时自动解绑；
 * - <b>异步感知</b>：监听器返回 {@link Promise} 时自动进入异步链路——
 *   serial/waterfall 会等待它结算后再继续，parallel 会收集它的异常，
 *   对应 TS 中“监听器返回 Promise 即被 await”的语义。
 *
 * 线程模型：所有派发动作统一投递到 {@link Scheduler} 主循环串行执行，
 * 因此 emit 可以从任意线程调用（典型场景：IO 回调线程里桥接事件）；
 * 监听器内部若需阻塞，必须返回 scheduler.offload(...) 产出的 Promise。
 */
public class EventsService {

    /** 事件名 -> 监听器列表（头部优先 + 尾部追加，见 DisposableList 的 prepend 语义） */
    private final Map<String, DisposableList<Hook>> hooks = new LinkedHashMap<>();
    private final Scheduler scheduler;

    public EventsService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 对应 on()：注册监听器并返回 Disposable。
     *
     * 注册动作与 effect 绑定都在主循环上完成：
     * - 监听器包装为 {@link Hook} 加入 hooks；
     * - 注册/解绑整对动作再包成 caller 所在 fiber 的 effect——fiber 卸载时
     *   由 {@link Fiber#unload()} 逆序执行清理，监听器随之解绑并重载时重新
     *   注册。这一步不可省略：漏绑会导致旧监听器在 fiber 卸载后继续派发，
     *   持有已关闭的连接/过期的服务引用。
     */
    public Disposable on(final Context caller, final String name, final EventListener listener) {
        return on(caller, name, listener, false);
    }

    /**
     * 对应 on(name, listener, prepend)：prepend=true 时插入列表头部，
     * 派发时先于已注册的同名监听器执行（对应 TS 的 unshift 分支）。
     */
    public Disposable on(final Context caller, final String name, final EventListener listener,
                        final boolean prepend) {
        final Hook[] holder = new Hook[1];
        final Disposable[] bound = new Disposable[1];
        final boolean[] removed = {false};
        scheduler.execute(() -> {
            if (removed[0]) return;
            final Hook hook = new Hook(caller, listener);
            holder[0] = hook;
            final Fiber owner = caller.fiber();
            if (owner != null && owner.acceptsEffects()) {
                // 注册动作即 effect 本体：立即执行一次，返回的清理函数负责解绑
                bound[0] = owner.effect(() -> {
                    register(name, hook, prepend);
                    return () -> unregister(name, hook);
                }, "events.on(" + name + ")");
            } else {
                // 宿主 fiber 已失效（或不存在）：退化为无主监听器，仅由调用方手动解绑
                register(name, hook, prepend);
            }
        });
        return () -> scheduler.execute(() -> {
            removed[0] = true;
            if (bound[0] != null) {
                bound[0].dispose();          // 走 fiber 的 effect 清理，等价于解绑 + 摘除 effect
            } else if (holder[0] != null) {
                unregister(name, holder[0]);
            }
        });
    }

    /** 把 hook 加入指定事件的列表；仅主循环调用 */
    private void register(String name, Hook hook, boolean prepend) {
        DisposableList<Hook> list = hooks.computeIfAbsent(name, k -> new DisposableList<Hook>());
        if (prepend) list.unshift(hook);
        else list.push(hook);
    }

    /** 对应 once()：先移除自身，再执行监听器 */
    public Disposable once(Context caller, String name, EventListener listener) {
        Disposable[] handle = new Disposable[1];
        handle[0] = on(caller, name, args -> {
            handle[0].dispose();
            return listener.invoke(args);
        });
        return handle[0];
    }

    /** 对应 _resolve：取出监听器快照（已移除的不计入）；仅主循环调用 */
    private List<Hook> snapshot(String name) {
        DisposableList<Hook> all = hooks.get(name);
        List<Hook> result = new ArrayList<>();
        if (all == null) return result;
        for (Hook hook : all) {
            if (!hook.removed) result.add(hook);
        }
        return result;
    }

    /** 对应 isBailed()：非 null 即视为“截获”，后续监听器不再执行 */
    private static boolean isBailed(Object value) {
        return value != null;
    }

    /**
     * emit：广播，所有监听器都会执行，返回值被忽略（fire-and-forget）。
     * 监听器返回的 Promise 若失败，记录为 unhandled rejection。
     */
    public void emit(final String name, final Object... args) {
        scheduler.execute(() -> {
            for (Hook hook : snapshot(name)) {
                Object out;
                try {
                    out = hook.callback.invoke(args);
                } catch (RuntimeException e) {
                    System.err.println("[cordis] listener error for \"" + name + "\" (" + describe(hook) + "): " + e);
                    continue;
                }
                if (out instanceof Promise) {
                    ((Promise<?>) out).catchError(e -> {
                        System.err.println("[cordis] unhandled rejection in listener of \"" + name
                                + "\" (" + describe(hook) + "): " + e);
                        return null;
                    });
                }
            }
        });
    }

    /**
     * serial：串行执行，遇到非 null 返回值立即截获。
     * 监听器返回 Promise 时会等待其结算：结算值非 null 截获，null 继续。
     * 对应 async serial()。
     */
    public Promise<Object> serial(final String name, final Object... args) {
        final Deferred<Object> result = scheduler.defer();
        scheduler.execute(() -> runSerial(result, snapshot(name), 0, args));
        return result.promise();
    }

    /**
     * bail：语义与 serial 相同；TS 中 bail 是纯同步版本（serial 会等待
     * Promise），Java 中统一走异步实现。
     */
    public Promise<Object> bail(String name, Object... args) {
        return serial(name, args);
    }

    /** serial 的递归链：执行第 index 个监听器并决定截获或继续 */
    private void runSerial(final Deferred<Object> result, final List<Hook> hooks,
                           final int index, final Object[] args) {
        if (index >= hooks.size()) {
            result.resolve(null);
            return;
        }
        Object out;
        try {
            out = hooks.get(index).callback.invoke(args);
        } catch (RuntimeException e) {
            result.reject(e);
            return;
        }
        // 注意分支顺序：Promise 必须先于 isBailed 判断——Promise 对象本身
        // 非 null，若先做 isBailed 会把“尚未结算的 Promise”当作截获值
        // 直接 resolve，而不是等待其结算（异步监听器从此失效）。
        if (out instanceof Promise) {
            ((Promise<Object>) out).handle((v, err) -> {
                if (err != null) result.reject(err);
                else if (isBailed(v)) result.resolve(v);
                else runSerial(result, hooks, index + 1, args);
            });
        } else if (isBailed(out)) {
            result.resolve(out);
        } else {
            runSerial(result, hooks, index + 1, args);
        }
    }

    /**
     * waterfall：洋葱/中间件模型。每个监听器接收“附加参数 + 上一个监听器
     * 的返回值”，其返回值继续向下传递（若返回 Promise 则等待结算），
     * 最终返回最后一个监听器的结果。对应 waterfall()。
     */
    public Promise<Object> waterfall(final String name, final Object value, final Object... args) {
        final Deferred<Object> result = scheduler.defer();
        scheduler.execute(() -> runWaterfall(result, snapshot(name), 0, args, value));
        return result.promise();
    }

    private void runWaterfall(final Deferred<Object> result, final List<Hook> hooks,
                              final int index, final Object[] args, final Object value) {
        if (index >= hooks.size()) {
            result.resolve(value);
            return;
        }
        Object[] full = new Object[args.length + 1];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = value;
        Object out;
        try {
            out = hooks.get(index).callback.invoke(full);
        } catch (RuntimeException e) {
            result.reject(e);
            return;
        }
        if (out instanceof Promise) {
            ((Promise<Object>) out).handle((v, err) -> {
                if (err != null) result.reject(err);
                else runWaterfall(result, hooks, index + 1, args, v);
            });
        } else {
            runWaterfall(result, hooks, index + 1, args, out);
        }
    }

    /**
     * parallel：并发执行所有监听器（allSettled 语义），全部结算后——
     * 有失败则 reject（聚合异常，suppressed 携带全部原因），否则 resolve。
     * 对应 parallel()（TS 中为 Promise.allSettled + AggregateError）。
     */
    public Promise<Void> parallel(final String name, final Object... args) {
        final Deferred<Void> result = scheduler.defer();
        scheduler.execute(() -> {
            List<Promise<Object>> outcomes = new ArrayList<>();
            for (Hook hook : snapshot(name)) {
                final Deferred<Object> d = scheduler.defer();
                outcomes.add(d.promise());
                Object out;
                try {
                    out = hook.callback.invoke(args);
                } catch (RuntimeException e) {
                    d.reject(e);
                    continue;
                }
                if (out instanceof Promise) {
                    ((Promise<Object>) out).handle((v, err) -> {
                        if (err != null) d.reject(err);
                        else d.resolve(v);
                    });
                } else {
                    d.resolve(out);
                }
            }
            Promise.<Object>allSettled(scheduler, outcomes).then(settled -> {
                List<Throwable> errors = new ArrayList<>();
                for (Promise.Settled<Object> s : settled) {
                    if (!s.success && s.error != null) errors.add(s.error);
                }
                if (errors.isEmpty()) {
                    result.resolve(null);
                } else {
                    IllegalStateException error = new IllegalStateException(
                            "one or more listeners of event \"" + name + "\" failed");
                    for (Throwable t : errors) error.addSuppressed(t);
                    result.reject(error);
                }
                return null;
            });
        });
        return result.promise();
    }

    /** 监听器归属描述（哪个 fiber 注册的），用于错误日志定位；仅主循环调用 */
    private static String describe(Hook hook) {
        Fiber owner = hook.owner.fiber();
        return "registered by " + (owner == null ? "<unknown>" : owner.name);
    }

    /** 对应 unregister()；仅主循环调用 */
    private void unregister(String name, Hook hook) {
        hook.removed = true;
        DisposableList<Hook> list = hooks.get(name);
        if (list != null) {
            list.delete(hook);
            if (list.size() == 0) hooks.remove(name);
        }
    }
}
