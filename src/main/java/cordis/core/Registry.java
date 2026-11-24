package cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** 组件 -> 运行时档案（对应 _internal：Map&lt;Function, Component.Runtime&gt;） */
    private final Map<Component, Runtime> internal = new LinkedHashMap<>();
    private int counter = 0;

    Registry(Context ctx) {
        this.ctx = ctx;
    }

    /** 递增的实例编号（对应 registry.counter） */
    public int counter() {
        return ++counter;
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
     *    isolate/intercept 标签都会被继承（多租户隔离的关键）；
     * 2. 插件的整个生命周期注册为 caller 沿父链解析出的 fiber 的 effect。
     */
    public Fiber use(Context caller, Component component,
                     Map<String, Object> config, Map<String, Object> inject) {
        Runtime runtime = internal.computeIfAbsent(component, c -> new Runtime(nameOf(c), c));
        final Fiber fiber = new Fiber(caller, runtime,
                config == null ? Collections.<String, Object>emptyMap() : config,
                inject == null ? Collections.<String, Object>emptyMap() : inject);
        runtime.fibers.add(fiber);
        // 挂载动作投递到主循环：use() 立即返回 fiber（可能仍是 PENDING），
        // 挂载完成时 fiber.ready() 会结算——对应 await fiber
        ctx.scheduler.execute(fiber::register);
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
     * 以及 Service 构造时触发的 reflect.notify）。
     */
    void onServiceChanged(String name) {
        for (Runtime runtime : internal.values()) {
            for (Fiber fiber : new ArrayList<>(runtime.fibers)) {
                if (fiber.inject.containsKey(name)) fiber.onDependenciesChanged();
            }
        }
    }
}
