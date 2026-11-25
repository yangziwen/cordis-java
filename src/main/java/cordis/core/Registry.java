package cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对应 packages/core/src/registry.ts 的 RegistryService。
 *
 * Registry 负责“定义 → 实例”的装配：组件（{@link Component}）第一次被
 * use() 时建立运行时档案（{@link Runtime}），每次 use() 产生一根
 * 新的 {@link Fiber} 并登记进档案。
 *
 * 服务出现/消失时，由本类唤醒 inject 中声明了该服务的 fiber
 * （对应 TS 的 reflect.notify / internal/service 事件）。
 */
public class Registry {

    private final Context ctx;

    /**
     * 组件 -> 运行时档案（对应 _internal：Map&lt;Function, Component.Runtime&gt;）。
     * 写只在主循环，但 {@link #size()} 是 public 且常被测试/编排层从外部轮询，
     * 故用并发容器（普通 HashMap 的并发读会读到陈旧的 size 甚至内部不一致）。
     */
    private final Map<Component, Runtime> internal = new ConcurrentHashMap<>();

    Registry(Context ctx) {
        this.ctx = ctx;
    }

    public int size() {
        return internal.size();
    }

    /**
     * 对应 registry.use()（TS 中通过 Proxy 绑定 thisArg）：在指定上下文上
     * 实例化一个插件，返回其 Fiber。组件名取自 component.getName()。
     *
     * caller 决定两件事：
     * 1. fiber 的专属上下文是 caller 的子节点，因此 caller 分支上的
     *    isolate 标签都会被继承（多租户隔离的关键）；
     * 2. 插件的整个生命周期注册为 caller 沿父链解析出的 fiber 的 effect。
     *
     * 线程模型：use() 可以在任意线程调用（同步返回 PENDING 状态的 fiber），
     * 但档案登记与挂载一律投递到主循环执行，共享的 internal / fibers 只在
     * 主循环上读写。
     */
    public Fiber use(final Context caller, final Component component,
                     Map<String, Object> config, Map<String, Object> inject) {
        final String name = nameOf(component);
        // Fiber 构造只动自己新派生的上下文，不触碰任何共享表：
        // 因此可以在任意线程安全地创建并立即返回给调用方。
        final Fiber fiber = new Fiber(caller, name, component,
                config == null ? Collections.<String, Object>emptyMap() : config,
                inject == null ? Collections.<String, Object>emptyMap() : inject);
        // 档案登记与挂载全部收敛到主循环：internal / runtime.fibers 是主循环
        // 侧（onServiceChanged 遍历）与本调用共用的可变结构，跨线程写会丢更新。
        ctx.scheduler.execute(() -> {
            Runtime runtime = internal.computeIfAbsent(component, c -> new Runtime(name, c));
            runtime.fibers.add(fiber);
            fiber.attachRuntime(runtime);
            try {
                fiber.register();
            } catch (Throwable t) {
                // 注册失败（典型：在已销毁的上下文上 use）也要让 fiber 可观测地失败，
                // 否则它会停在 PENDING、await() 却返回成功。
                fiber.fail(t);
            }
        });
        return fiber;
    }

    /**
     * 便捷重载：等价于在根上下文上调用 use(root, ...)。
     * 注意：通过隔离上下文加载插件时必须用带 caller 的重载，
     * 否则隔离标签不会生效。
     */
    public Fiber use(Component component,
                     Map<String, Object> config, Map<String, Object> inject) {
        return use(ctx, component, config, inject);
    }

    public Fiber use(Component component) {
        return use(ctx, component, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap());
    }

    /** 解析组件名，对应 component.name；匿名组件退化为 "anonymous" */
    private static String nameOf(Component component) {
        String name = component.getName();
        return name != null ? name : "anonymous";
    }

    /**
     * 服务出现/消失时唤醒等待它的 fiber（对应 internal/service 事件，
     * 以及 Service 构造时触发的 reflect.notify）。仅主循环调用。
     *
     * 必须遍历快照：被唤醒的 fiber 可能因依赖消失而卸载、进而级联销毁其它
     * fiber，{@link #retire(Fiber)} 会从 internal 中摘除条目——直接遍历
     * internal.values() 会 ConcurrentModificationException。
     */
    void onServiceChanged(String name) {
        for (Runtime runtime : new ArrayList<>(internal.values())) {
            for (Fiber fiber : new ArrayList<>(runtime.fibers)) {
                if (fiber.inject.containsKey(name)) {
                    fiber.onDependenciesChanged();
                }
            }
        }
    }

    /**
     * fiber 销毁后回收档案：最后一个 fiber 消失时把整份 Runtime 一并注销，
     * 对应 Runtime 注释描述的行为（否则 internal 会随 use/dispose 循环无限增长，
     * 且强引用住 Component 与配置）。仅主循环调用。
     */
    void retire(Fiber fiber) {
        Runtime runtime = fiber.runtime();
        if (runtime == null) {
            return;                                     // 根 fiber 不在档案里
        }
        runtime.fibers.remove(fiber);
        if (runtime.fibers.isEmpty()) {
            internal.remove(runtime.callback, runtime);     // 双参数移除，避免误删重建后的新档案
        }
    }

}
