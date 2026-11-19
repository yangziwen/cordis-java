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
     * 对应 registry.use()：在当前上下文中实例化一个插件，返回其 Fiber。
     * 构造函数内会立即尝试挂载（refresh -> reload）。
     */
    public Fiber use(String name, Component component,
                     Map<String, Object> config, Map<String, Object> inject) {
        Runtime runtime = internal.computeIfAbsent(component, c -> new Runtime(name, c));
        Fiber fiber = new Fiber(ctx, runtime,
                config == null ? Collections.<String, Object>emptyMap() : config,
                inject == null ? Collections.<String, Object>emptyMap() : inject);
        runtime.fibers.add(fiber);
        return fiber;
    }

    public Fiber use(String name, Component component) {
        return use(name, component, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap());
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
