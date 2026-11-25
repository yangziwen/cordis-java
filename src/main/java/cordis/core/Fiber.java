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
 * - await() 每次返回全新的 Promise（对应 TS Fiber.await 的 async 函数），
 *   编排层用它等待当前生命周期事务结算
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

    /** 当前生命周期状态（主循环写入；orchestration 线程也会读，故 volatile） */
    public volatile FiberState state = FiberState.PENDING;

    /**
     * 本 fiber 的运行时登记信息（对应 runtime），根 fiber 为 null。
     * 非 final：档案在主循环的 use() 任务里挂上（见 Registry.use），
     * 构造阶段不触碰任何共享登记表。
     */
    private Runtime runtime;
    /** 本 fiber 提供的服务（对应 provisions），卸载时随之撤销 */
    final List<Service> provisions = new ArrayList<>();
    /** 本 fiber 收集的所有可逆 effect（对应 _disposables） */
    private final DisposableList<Disposable> disposables = new DisposableList<>();
    /** 注册在父 fiber 上的 effect，dispose 时把整个插件从父上下文上撤销 */
    private Disposable registration;
    /** 当前正在飞的生命周期事务（对应 TS inertia）：非 null 表示有 reload 未结算，await() 等它 */
    private Promise<Void> inertia;
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
    }

    /**
     * 插件 fiber，对应 Fiber 构造函数中 runtime != null 的分支。
     * 构造只做登记，不挂载——挂载由 {@link #register()} 在主循环上执行。
     * 名字/主体直接取自组件定义，因此可以在任意线程构造（字段均为本实例私有）。
     */
    Fiber(Context parent, String name, Component component,
          Map<String, Object> config, Map<String, Object> inject) {
        this.ctx = parent.extend();
        this.parent = parent;
        ctx.setFiber(this);
        this.name = name;
        this.component = component;
        this.config = config;
        this.inject = inject;
    }

    /** 在主循环上绑定运行时档案（对应 registry._internal 中的登记） */
    void attachRuntime(Runtime runtime) {
        this.runtime = runtime;
    }

    /** 本 fiber 的运行时档案（根 fiber 为 null） */
    Runtime runtime() {
        return runtime;
    }

    /**
     * 本 fiber 现在能否接受新的 effect：已销毁或已失效（epochActive=false）
     * 的 fiber 上不能再注册资源，否则资源会随 fiber 一起变成孤儿。
     */
    public boolean acceptsEffects() {
        return epochActive && state != FiberState.DISPOSED;
    }

    /**
     * 主循环上执行：把整个插件生命周期注册为父 fiber 的 effect。
     * 对应 `this.dispose = parent.fiber.effect(() => { ... }, 'ctx.use()')`。
     */
    void register() {
        this.registration = parent.fiber().effect(() -> {
            this.epochActive = true;
            this.refresh();
            return this::markDisposed;
        }, "registry.use(" + name + ")");
    }

    /**
     * 收尾：撤销全部 effect 与服务，标记 DISPOSED，并回收运行时档案。
     *
     * 两条路径都汇到这里——显式 {@link #dispose()}，以及父 fiber 卸载时对本
     * fiber 的级联清理（子 fiber 的生命周期本来就是父 fiber 的 effect）。
     * 档案回收必须挂在“变 DISPOSED”这一点上：只写在 dispose() 里的话，级联
     * 销毁的子 fiber 会永远留在 registry 里。
     */
    private void markDisposed() {
        this.epochActive = false;
        this.unload();
        this.state = FiberState.DISPOSED;
        ctx.registry.retire(this);
    }

    /**
     * 对应 TS Fiber.await()：等待当前正在飞的生命周期事务（inertia）结算后返回。
     * 与 TS 的 async 函数一致，<b>每次调用都产生一个全新的 Promise</b>，
     * 不复用、不封版：
     * <ul>
     *   <li>调用瞬间有事务在飞（LOADING）→ 等它结算，再按最新 error 判定；</li>
     *   <li>调用瞬间 fiber 静止 → 立即结算：error != null 则 reject
     *       （最近一次 reload 的失败原因），否则 resolve(this)——
     *       哪怕 state 仍是 PENDING（等依赖），与 TS 语义完全一致。</li>
     * </ul>
     * 因此后续每一次 reload 的成功/失败都能被新一次 await() 观察到，
     * 不存在“首次结果永久封版”的问题。
     */
    public Promise<Fiber> await() {
        final Deferred<Fiber> deferred = ctx.scheduler.defer();
        ctx.scheduler.execute(() -> {
            if (inertia != null) {
                // 有事务在飞：等它结算后重新判定
                // （对应 TS 的 while (this.inertia) await this.inertia）
                inertia.handle((v, err) -> settleAwait(deferred));
            } else {
                settleAwait(deferred);
            }
        });
        return deferred.promise();
    }

    /** await 的结算判定：只看最新 error（对应 TS 的 if (this._error) throw） */
    private void settleAwait(Deferred<Fiber> deferred) {
        if (error != null) {
            deferred.reject(error);
        } else {
            deferred.resolve(this);
        }
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
     *
     * <b>必须在主循环上调用</b>：effect 表是本 fiber 的共享状态，且 execute
     * 会立即执行——跨线程注册既破坏无锁前提，也会让清理顺序不可预期。
     */
    public Disposable effect(Supplier<Disposable> execute, String label) {
        assertActive();
        // effect 表是本 fiber 的共享状态，只在主循环上读写
        ctx.scheduler.assertInMainLoop("fiber.effect(" + label + ")");
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
        // 事务化：先把在飞事务登记进 inertia，再启动加载。
        // 否则同步插件整条链会在赋值前就跑完，收尾的 handle 先把 inertia 置 null，
        // 随后又被赋值回一个已结算的 Promise——inertia 永远非 null，“有事务在飞”
        // 的判断从此失真。
        final Deferred<Void> transaction = ctx.scheduler.defer();
        final Promise<Void> inFlight = transaction.promise();
        this.inertia = inFlight;
        component.applyAsync(ctx, config)
                .then(cleanup -> {
                    // 等待期间可能已被 dispose/依赖消失：丢弃迟到的结果
                    if (!epochActive || state != FiberState.LOADING) {
                        if (cleanup != null) cleanup.dispose();
                        return (Void) null;
                    }
                    if (cleanup != null) disposables.push(cleanup);
                    error = null;
                    state = FiberState.ACTIVE;
                    // 对应 _updateState 中 ACTIVE 变更时的 provisions 通知：
                    // 本 fiber 提供的服务此时才对其他 fiber 可见
                    for (Service s : new ArrayList<>(provisions)) {
                        ctx.registry.onServiceChanged(s.name);
                    }
                    return (Void) null;
                })
                .catchError(e -> {
                    if (!epochActive) return null;
                    // 失败即回滚：apply 里可能已经 provide 了服务、注册了监听器，
                    // 不回收会让服务名被永久占用（后续 reload 必然撞名冲突），
                    // 连接/监听器也会泄漏。对应 TS _reload 失败分支的清理。
                    rollback();
                    fail(e);
                    return null;
                })
                // 对应 TS _reload 收尾的 inertia = undefined：
                // 只清理属于自己的事务（期间可能已经是新一次 reload 的事务）
                .handle((v, err) -> {
                    if (this.inertia == inFlight) this.inertia = null;
                    transaction.resolve((Void) null);
                });
    }

    /**
     * 对应 _unload()：按注册的逆序执行所有清理函数，然后撤销本 fiber
     * 提供的服务（撤销会进一步触发依赖这些服务的 fiber 卸载/重载）。
     */
    private void unload() {
        state = FiberState.UNLOADING;
        disposeAll();
        releaseProvisions();
        if (epochActive) state = FiberState.PENDING;
    }

    /**
     * 加载失败后的回滚：apply 已经注册的 effect（监听器等）与已发布的服务
     * 全部撤销，让 fiber 回到可重试的干净状态。
     */
    private void rollback() {
        disposeAll();
        releaseProvisions();
    }

    /** 逆序执行并清空本 fiber 收集的 effect */
    private void disposeAll() {
        List<Disposable> all = disposables.clear();
        for (int i = all.size() - 1; i >= 0; i--) {
            try {
                all.get(i).dispose();
            } catch (RuntimeException e) {
                // 对应 _unload 中被 logger.error 吞掉的清理异常
                System.err.println("[cordis] error while unloading \"" + name + "\": " + e);
            }
        }
    }

    /** 撤销本 fiber 提供的全部服务，对应 provisions 的清理 */
    private void releaseProvisions() {
        for (Service s : new ArrayList<>(provisions)) {
            ctx.unprovide(s.name, s);
        }
        provisions.clear();
    }

    /**
     * 把本 fiber 标记为加载失败。
     * 主循环上执行；await() 会以该 error reject，而不是静默返回成功。
     */
    void fail(Throwable e) {
        if (state == FiberState.DISPOSED) return;
        error = e;
        state = FiberState.FAILED;
        System.err.println("[cordis] fiber \"" + name + "\" failed: " + e);
    }

    /** 服务出现/消失时由 Registry 回调（对应 internal/service 事件触发的刷新） */
    void onDependenciesChanged() {
        refresh();
    }

    /**
     * 对应 fiber.dispose：把整个插件从父上下文上撤销。
     * 投递到主循环执行，保证与状态机串行。
     *
     * 根 fiber（runtime == null）不可销毁；挂载从未成功的 fiber（register 抛过
     * 异常，registration 仍为 null）也要能正常收尾，否则它会永远占着档案。
     */
    public void dispose() {
        ctx.scheduler.execute(() -> {
            if (runtime == null) return;                 // 根 fiber 不可销毁
            if (state != FiberState.DISPOSED) {
                if (registration != null) {
                    registration.dispose();              // 走父 fiber 的 effect 清理 → markDisposed
                } else {
                    // 挂载从未成功（register 抛过异常）：直接收尾，否则它会永远占着档案
                    markDisposed();
                }
            }
            ctx.registry.retire(this);                   // 幂等兜底
        });
    }

    /** 插件加载失败的原因（对应 _error，await() 携带的最新失败） */
    public Throwable getError() {
        return error;
    }

    @Override
    public String toString() {
        return "Fiber(" + name + ", " + state + ")";
    }
}
