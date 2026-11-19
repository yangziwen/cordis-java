package cordis.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 packages/core/src/events.ts。
 *
 * cordis 的事件系统比普通 EventEmitter 强得多：
 * - 同一个事件名支持 5 种派发模式（emit / parallel / serial / bail / waterfall）；
 * - 监听器以 effect 的形式挂在“注册方所在的 fiber”上，fiber 卸载时自动解绑
 *   （对应 register() 里的 {@code this.ctx.fiber.effect(...)}）。
 *
 * TS 中 {@code ctx.events.on(...)} 的 thisArg 是由 Proxy 绑定到调用方上下文的；
 * Java 没有隐式 thisArg，因此注册方法需要显式传入调用方上下文（见
 * {@link Context#on} 便捷方法），派发方法则在共享实例上全局广播。
 *
 * TS 版本在派发时还会通过 thisArg 上的 [symbols.filter]（即 Service 的
 * isolate 过滤）筛选监听器；Java 范例中该过滤已由 Service.applicable()
 * 承载，事件派发保持全局广播，故不再重复实现。
 */
public class EventsService {

    /** 对应 Hook 的 callback：可变参数的监听器 */
    public interface Listener {
        Object invoke(Object... args);
    }

    /** 对应 Hook：一个带所属上下文的监听器 */
    private static class Hook {
        /** 注册监听器时的上下文（对应 hook.ctx）；TS 中用于 isolate 过滤，本范例保留备查 */
        @SuppressWarnings("unused")
        final Context owner;
        final Listener callback;
        boolean removed;

        Hook(Context owner, Listener callback) {
            this.owner = owner;
            this.callback = callback;
        }
    }

    private final Map<String, List<Hook>> hooks = new LinkedHashMap<>();

    /**
     * 对应 on()：注册监听器并返回 Disposable。
     * 监听器注册为调用方所在 fiber 的 effect，fiber 卸载时自动解绑。
     */
    public Disposable on(Context caller, String name, Listener listener) {
        return caller.fiber().effect(() -> {
            Hook hook = new Hook(caller, listener);
            hooks.computeIfAbsent(name, k -> new ArrayList<>()).add(hook);
            return () -> unregister(name, hook);
        }, "ctx.on(\"" + name + "\")");
    }

    /** 对应 once()：先移除自身，再执行监听器 */
    public Disposable once(Context caller, String name, Listener listener) {
        Disposable[] handle = new Disposable[1];
        handle[0] = on(caller, name, args -> {
            handle[0].dispose();
            return listener.invoke(args);
        });
        return handle[0];
    }

    /** 对应 _resolve：取出监听器快照（已移除的不计入） */
    private List<Hook> snapshot(String name) {
        List<Hook> all = hooks.get(name);
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
     * emit：同步广播，所有监听器都会执行，返回值被忽略。
     * 对应 emit()。
     */
    public void emit(String name, Object... args) {
        for (Hook hook : snapshot(name)) {
            hook.callback.invoke(args);
        }
    }

    /**
     * serial：串行执行，遇到非 null 返回值立即截获并返回。
     * 对应 async serial()（TS 版本会 await 每个监听器）。
     */
    public Object serial(String name, Object... args) {
        for (Hook hook : snapshot(name)) {
            Object result = hook.callback.invoke(args);
            if (isBailed(result)) return result;
        }
        return null;
    }

    /**
     * bail：语义与 serial 相同；TS 中 bail 是纯同步版本（serial 会等待
     * Promise），Java 没有此区分，两者共用实现。
     */
    public Object bail(String name, Object... args) {
        return serial(name, args);
    }

    /**
     * waterfall：洋葱/中间件模型。每个监听器接收“附加参数 + 上一个监听器
     * 的返回值”，其返回值继续向下传递，最终返回最后一个监听器的结果。
     * 对应 waterfall()。
     */
    public Object waterfall(String name, Object value, Object... args) {
        for (Hook hook : snapshot(name)) {
            Object[] full = new Object[args.length + 1];
            System.arraycopy(args, 0, full, 0, args.length);
            full[args.length] = value;
            value = hook.callback.invoke(full);
        }
        return value;
    }

    /**
     * parallel：收集所有监听器的异常后合并抛出。
     * 对应 parallel()（TS 中为 Promise.allSettled + AggregateError）。
     */
    public void parallel(String name, Object... args) {
        List<RuntimeException> errors = new ArrayList<>();
        for (Hook hook : snapshot(name)) {
            try {
                hook.callback.invoke(args);
            } catch (RuntimeException e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            IllegalStateException error = new IllegalStateException(
                    "one or more listeners of event \"" + name + "\" failed");
            for (RuntimeException e : errors) error.addSuppressed(e);
            throw error;
        }
    }

    /** 对应 unregister() */
    private void unregister(String name, Hook hook) {
        hook.removed = true;
        List<Hook> list = hooks.get(name);
        if (list != null) {
            list.remove(hook);
            if (list.isEmpty()) hooks.remove(name);
        }
    }
}
