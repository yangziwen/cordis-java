package cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对应 packages/core/src/fiber.ts。
 *
 * Fiber 是插件实例的生命周期容器：一个 Component 在某个上下文中被 use()
 * 一次，就产生一个 Fiber。它维护三样东西：
 *
 * 1. 状态机（PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED）；
 * 2. 依赖清单 inject：声明的服务全部就绪才激活，任一依赖消失就卸载
 *    （对应 _refresh() 中基于 epoch 的依赖追踪）；
 * 3. 一组可逆的 effect：插件运行期间注册的所有资源，卸载时按注册的
 *    逆序清理（对应 _disposables 与 _unload()）。
 *
 * TS 版本的 reload/unload 是异步的（基于 Promise 的 inertia），Java 范例
 * 为了保持清晰改为同步执行，但状态机的骨架保持一致：
 *
 *   PENDING ──依赖就绪──> LOADING ──执行成功──> ACTIVE
 *      ^                                              │
 *      └────依赖缺失（refresh）<────UNLOADING<────────┘
 *
 * 整个插件本身是父上下文 fiber 上的一个 effect（对应构造函数中的
 * {@code this.dispose = parent.fiber.effect(...)}），因此销毁插件与销毁
 * 普通资源遵循同一套逆序清理规则。
 */
public class Fiber {
    /** 本 fiber 对应的上下文（一个 extend 出来的子上下文，对应 this.ctx） */
    public final Context ctx;
    /** 插件名（对应 runtime.name） */
    public final String name;
    /** 插件主体（对应 runtime.callback） */
    public final Registry.Component component;
    /** 插件配置（对应 config） */
    public final Map<String, Object> config;
    /** 声明的依赖：服务名 -> 要求（对应 inject） */
    public final Map<String, Object> inject;

    public FiberState state = FiberState.PENDING;

    /** 本 fiber 的运行时登记信息（对应 runtime），根 fiber 为 null */
    private final Registry.Runtime runtime;
    /** 本 fiber 提供的服务（对应 provisions），卸载时随之撤销 */
    final List<Service> provisions = new ArrayList<>();
    /** 本 fiber 收集的所有可逆 effect（对应 _disposables） */
    private final DisposableList<Disposable> disposables = new DisposableList<>();
    /** 注册在父 fiber 上的 effect，dispose 时把整个插件从父上下文上撤销 */
    private final Disposable registration;
    /** 对应 runner.epoch：effect 是否仍然有效 */
    private boolean epochActive;
    private Throwable error;

    /** 根 fiber，对应 Fiber 构造函数中 runtime == null 的分支 */
    Fiber(Context owner) {
        this.ctx = owner;
        this.runtime = null;
        this.name = "root";
        this.component = null;
        this.config = Collections.emptyMap();
        this.inject = Collections.emptyMap();
        this.registration = null;
        this.epochActive = true;
        this.state = FiberState.ACTIVE;
    }

    /** 插件 fiber，对应 Fiber 构造函数中 runtime != null 的分支 */
    Fiber(Context parent, Registry.Runtime runtime,
          Map<String, Object> config, Map<String, Object> inject) {
        this.ctx = parent.extend();
        ctx.setFiber(this);
        this.runtime = runtime;
        this.name = runtime.name;
        this.component = runtime.callback;
        this.config = config;
        this.inject = inject;

        // 对应 `this.dispose = parent.fiber.effect(() => { ... }, 'ctx.use()')`：
        // 插件的整个生命周期是父上下文上的一个 effect。
        // execute 部分负责挂载（refresh -> reload），返回值负责卸载。
        this.registration = parent.fiber().effect(() -> {
            this.epochActive = true;
            this.refresh();
            return () -> {
                this.epochActive = false;
                this.unload();
                this.state = FiberState.DISPOSED;
            };
        }, "registry.use(" + name + ")");
    }

    /** 对应 assertActive()：在已销毁的上下文上创建 effect 属于 INACTIVE_EFFECT 错误 */
    public void assertActive() {
        if (epochActive) return;
        throw new IllegalStateException("cannot create effect on inactive context (INACTIVE_EFFECT)");
    }

    /**
     * 对应 fiber.effect(execute, label)。
     * 立即执行 execute 一次，收集它（可选）返回的清理函数；返回的 Disposable
     * 会把这些清理函数按注册的逆序执行，并把 effect 从本 fiber 上移除。
     */
    public Disposable effect(Supplier<Disposable> execute, String label) {
        assertActive();
        List<Disposable> collected = new ArrayList<>();
        Disposable unwind = () -> {
            for (int i = collected.size() - 1; i >= 0; i--) collected.get(i).dispose();
            collected.clear();
        };
        try {
            Disposable d = execute.get();
            if (d != null) collected.add(d);
        } catch (RuntimeException e) {
            unwind.dispose(); // 对应 catch 分支中的 dispose()
            throw e;
        }
        Disposable self = disposables.push(unwind);
        return () -> {
            self.dispose();
            unwind.dispose();
        };
    }

    /**
     * 对应 _refresh()：根据依赖是否全部就绪，驱动 fiber 在 ACTIVE 与 PENDING
     * 之间迁移（对应基于 epoch 字符串的依赖追踪，此处简化为布尔判断）。
     */
    void refresh() {
        if (!epochActive || state == FiberState.DISPOSED) return;
        if (dependenciesSatisfied()) {
            if (state != FiberState.ACTIVE) reload();
        } else if (state == FiberState.ACTIVE || state == FiberState.FAILED) {
            unload();
        }
    }

    private boolean dependenciesSatisfied() {
        for (String name : inject.keySet()) {
            if (ctx.get(name) == null) return false;
        }
        return true;
    }

    /** 对应 _reload()：执行插件主体，收集其返回的清理函数 */
    private void reload() {
        state = FiberState.LOADING;
        try {
            Disposable cleanup = component.apply(ctx, config);
            if (cleanup != null) disposables.push(cleanup);
            error = null;
            state = FiberState.ACTIVE;
            // 对应 _updateState 中 ACTIVE 变更时的 provisions 通知：
            // 本 fiber 提供的服务此时才对其他 fiber 可见
            for (Service s : new ArrayList<>(provisions)) {
                ctx.registry.onServiceChanged(s.name);
            }
        } catch (RuntimeException e) {
            error = e;
            state = FiberState.FAILED;
            System.err.println("[cordis] fiber \"" + name + "\" failed: " + e);
        }
    }

    /**
     * 对应 _unload()：按注册的逆序执行所有清理函数，然后撤销本 fiber
     * 提供的服务（撤销会进一步触发依赖这些服务的 fiber 卸载/重载）。
     */
    private void unload() {
        state = FiberState.UNLOADING;
        List<Disposable> all = disposables.clear();
        for (int i = all.size() - 1; i >= 0; i--) {
            try {
                all.get(i).dispose();
            } catch (RuntimeException e) {
                // 对应 _unload 中被 logger.error 吞掉的清理异常
                System.err.println("[cordis] error while unloading \"" + name + "\": " + e);
            }
        }
        for (Service s : new ArrayList<>(provisions)) {
            ctx.unprovide(s.name, s);
        }
        provisions.clear();
        if (epochActive) state = FiberState.PENDING;
    }

    /** 服务出现/消失时由 Registry 回调（对应 internal/service 事件触发的刷新） */
    void onDependenciesChanged() {
        refresh();
    }

    /** 对应 fiber.dispose：把整个插件从父上下文上撤销 */
    public void dispose() {
        if (registration == null) return; // 根 fiber 不可销毁
        registration.dispose();
        if (runtime != null) runtime.fibers.remove(this);
    }

    /** 插件加载失败的原因（对应 _error，await() 时抛出） */
    public Throwable getError() {
        return error;
    }

    @Override
    public String toString() {
        return "Fiber(" + name + ", " + state + ")";
    }
}
