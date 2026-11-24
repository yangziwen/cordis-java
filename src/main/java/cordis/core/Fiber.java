package cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对应 packages/core/src/fiber.ts（异步化版本）。
 *
 * Fiber 是插件实例的生命周期容器：一个 Component 在某个上下文中被 use()
 * 一次，就产生一个 Fiber。它维护三样东西：
 *
 * 1. 状态机（PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED）；
 * 2. 依赖清单 inject：声明的服务全部就绪才激活，任一依赖消失就卸载；
 * 3. 一组可逆的 effect：插件运行期间注册的所有资源，卸载时按注册的逆序清理。
 *
 * <b>异步语义</b>（对应 TS 基于 Promise 的 reload/unload）：
 *
 *   PENDING ──依赖就绪──> LOADING ──applyAsync 结算成功──> ACTIVE
 *      ^                                                      │
 *      └────依赖缺失（refresh）<────UNLOADING<────────────────┘
 *
 * - applyAsync 结算前 fiber 停在 LOADING，主循环照常调度其他 fiber，
 *   因此慢初始化的插件不会阻塞任何人的挂载；
 * - 所有状态迁移只发生在 Scheduler 主循环线程上（use/dispose 亦被投递
 *   到主循环），整个状态机无锁；
 * - ready() 返回 Promise，编排层可等待插件就绪（对应 await fiber）。
 */
public class Fiber {
    /** 本 fiber 对应的上下文（一个 extend 出来的子上下文，对应 this.ctx） */
    public final Context ctx;
    /** 调用 use() 的上下文（注册 effect 的宿主），根 fiber 为 null */
    private final Context parent;
    /** 插件名（对应 runtime.name） */
    public final String name;
    /** 插件主体（对应 runtime.callback） */
    public final Component component;
    /** 插件配置（对应 config） */
    public final Map<String, Object> config;
    /** 声明的依赖：服务名 -> 要求（对应 inject） */
    public final Map<String, Object> inject;

    public FiberState state = FiberState.PENDING;

    /** 本 fiber 的运行时登记信息（对应 runtime），根 fiber 为 null */
    private final Runtime runtime;
    /** 本 fiber 提供的服务（对应 provisions），卸载时随之撤销 */
    final List<Service> provisions = new ArrayList<>();
    /** 本 fiber 收集的所有可逆 effect（对应 _disposables） */
    private final DisposableList<Disposable> disposables = new DisposableList<>();
    /** 注册在父 fiber 上的 effect，dispose 时把整个插件从父上下文上撤销 */
    private Disposable registration;
    /** 就绪信号：首次 ACTIVE 时 resolve，FAILED/DISPOSED 时 reject */
    private final Deferred<Fiber> ready;
    /** 对应 runner.epoch：effect 是否仍然有效 */
    private boolean epochActive;
    private Throwable error;

    /** 根 fiber，对应 Fiber 构造函数中 runtime == null 的分支 */
    Fiber(Context owner) {
        this.ctx = owner;
        this.parent = null;
        this.runtime = null;
        this.name = "root";
        this.component = null;
        this.config = Collections.emptyMap();
        this.inject = Collections.emptyMap();
        this.registration = null;
        this.epochActive = true;
        this.state = FiberState.ACTIVE;
        this.ready = owner.scheduler.defer();
        this.ready.resolve(this);
    }

    /**
     * 插件 fiber，对应 Fiber 构造函数中 runtime != null 的分支。
     * 构造只做登记，不挂载——挂载由 {@link #register()} 在主循环上执行。
     */
    Fiber(Context parent, Runtime runtime,
          Map<String, Object> config, Map<String, Object> inject) {
        this.ctx = parent.extend();
        this.parent = parent;
        ctx.setFiber(this);
        this.runtime = runtime;
        this.name = runtime.name;
        this.component = runtime.callback;
        this.config = config;
        this.inject = inject;
        this.ready = ctx.scheduler.defer();
    }

    /**
     * 主循环上执行：把整个插件生命周期注册为父 fiber 的 effect。
     * 对应 `this.dispose = parent.fiber.effect(() => { ... }, 'ctx.use()')`。
     */
    void register() {
        this.registration = parent.fiber().effect(() -> {
            this.epochActive = true;
            this.refresh();
            return () -> {
                this.epochActive = false;
                this.unload();
                this.state = FiberState.DISPOSED;
                this.ready.reject(new IllegalStateException("fiber \"" + name + "\" disposed before ready"));
            };
        }, "registry.use(" + name + ")");
    }

    /**
     * 就绪信号（对应 await fiber）：首次进入 ACTIVE 时 resolve，
     * FAILED / DISPOSED 时 reject。编排层用它等待插件挂载完成。
     */
    public Promise<Fiber> ready() {
        return ready.promise();
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

    /**
     * 对应 _reload()：调用 applyAsync 并等待其结算——结算成功进入 ACTIVE，
     * 失败进入 FAILED。结算前 fiber 停在 LOADING，不阻塞主循环。
     */
    private void reload() {
        state = FiberState.LOADING;
        component.applyAsync(ctx, config)
                .then(cleanup -> {
                    // 等待期间可能已被 dispose/依赖消失：丢弃迟到的结果
                    if (!epochActive || state != FiberState.LOADING) {
                        if (cleanup != null) cleanup.dispose();
                        return null;
                    }
                    if (cleanup != null) disposables.push(cleanup);
                    error = null;
                    state = FiberState.ACTIVE;
                    // 对应 _updateState 中 ACTIVE 变更时的 provisions 通知：
                    // 本 fiber 提供的服务此时才对其他 fiber 可见
                    for (Service s : new ArrayList<>(provisions)) {
                        ctx.registry.onServiceChanged(s.name);
                    }
                    ready.resolve(this);
                    return null;
                })
                .catchError(e -> {
                    if (!epochActive) return null;
                    error = e;
                    state = FiberState.FAILED;
                    System.err.println("[cordis] fiber \"" + name + "\" failed: " + e);
                    ready.reject(e);
                    return null;
                });
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

    /**
     * 对应 fiber.dispose：把整个插件从父上下文上撤销。
     * 投递到主循环执行，保证与状态机串行。
     */
    public void dispose() {
        ctx.scheduler.execute(() -> {
            if (registration == null) return; // 根 fiber 不可销毁
            registration.dispose();
            if (runtime != null) runtime.fibers.remove(this);
        });
    }

    /** 插件加载失败的原因（对应 _error，ready() reject 时携带） */
    public Throwable getError() {
        return error;
    }

    @Override
    public String toString() {
        return "Fiber(" + name + ", " + state + ")";
    }
}
