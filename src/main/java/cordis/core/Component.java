package cordis.core;

import java.util.Map;

/**
 * 对应 packages/core/src/registry.ts 的 Component：
 * 组件/插件的静态定义 —— cordis 的最小复用单元。
 *
 * 接收 (ctx, config)，在 apply 中完成初始化——注册服务、监听事件、创建
 * effect——并可选地返回一个清理函数（对应 TS 中插件返回 Disposable 或
 * AsyncDisposable）。
 *
 * 对应 TS 的 Component.Function / Component.Constructor / Component.Object
 * 三种形态，Java 用单一函数式接口 + 类实现来覆盖。
 *
 * 与 {@link Runtime}（登记档案）和 {@link Fiber}（运行实例）的关系：
 * 一份定义 → 在 registry 中对应一份档案 → 可产生任意多根 fiber。
 */
@FunctionalInterface
public interface Component {

    /**
     * 组件名，对应 TS 中 Component.Base 的 name 属性（JS 函数的固有名字）。
     * 名字属于定义而非实例：同一组件的所有 fiber 共享这个名字，
     * 仅用于日志/调试展示，不参与任何查找（服务名才是 key）。
     *
     * 返回 null 表示匿名组件（对应 TS 中 name === undefined）。
     * lambda 无法携带名字，需要命名的组件可用 {@link #named} 包装，
     * 或用类形态实现并覆写本方法。
     */
    default String getName() {
        return null;
    }

    /**
     * 给匿名 lambda 组件起名字的包装辅助（对应 TS 中给函数 defineProperty "name"）。
     * 注意：每次调用产生一个新的组件实例（拥有独立的 Runtime 档案）。
     */
    static Component named(String name, Component delegate) {
        return new Component() {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public Disposable apply(Context ctx, Map<String, Object> config) {
                return delegate.apply(ctx, config);
            }

        };
    }

    /**
     * 插件主体（同步路径，保持 @FunctionalInterface，lambda 直接可用）。
     *
     * 约束：必须轻量——运行在主循环线程上，禁止阻塞（JDBC/HTTP/文件 IO）。
     * 初始化涉及阻塞工作时，覆写 {@link #applyAsync}，把阻塞工作 offload 到
     * ctx.scheduler().offload(...)，此时 apply 不会被调用。
     *
     * @param ctx    本插件实例的专属上下文（fiber 的 ctx）
     * @param config 加载配置
     * @return 可选的清理函数；fiber 卸载时会被逆序调用
     */
    Disposable apply(Context ctx, Map<String, Object> config);

    /**
     * 异步插件主体（对应 TS 中插件返回 asyncDisposable / Promise）。
     * fiber 在返回的 Promise 完成前停留在 LOADING 状态，期间不阻塞主循环、
     * 不阻塞其他插件挂载。
     *
     * 默认实现直接同步调用 {@link #apply}（适用于轻量插件）。
     * apply 抛出的同步异常会被转成 rejected Promise——否则异常会逃逸到
     * 主循环任务里，fiber 永远停在 LOADING、inertia 永不结算、await() 永久悬挂。
     */
    default Promise<Disposable> applyAsync(Context ctx, Map<String, Object> config) {
        try {
            return ctx.scheduler.resolved(apply(ctx, config));
        } catch (Throwable t) {
            return Promise.rejected(ctx.scheduler, t);
        }
    }

}
