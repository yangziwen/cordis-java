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
     * 插件主体。
     *
     * @param ctx    本插件实例的专属上下文（fiber 的 ctx）
     * @param config 加载配置
     * @return 可选的清理函数；fiber 卸载时会被逆序调用
     */
    Disposable apply(Context ctx, Map<String, Object> config);
}
