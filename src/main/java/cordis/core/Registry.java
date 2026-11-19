package cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对应 packages/core/src/registry.ts。
 *
 * Component（组件/插件）是 cordis 的最小复用单元：接收 (ctx, config)，
 * 在 apply 中完成初始化——注册服务、监听事件、创建 effect——并可选地返回
 * 一个清理函数（对应 TS 中插件返回 Disposable 或 AsyncDisposable）。
 *
 * 对应 TS 的 Component.Function / Component.Constructor / Component.Object
 * 三种形态，Java 用单一函数式接口 + 类实现来覆盖。
 */
public class Registry {

    /** 对应 Component：{@code (ctx, config) => Disposable | void} */
    @FunctionalInterface
    public interface Component {
        Disposable apply(Context ctx, Map<String, Object> config);
    }

    /** 对应 Component.Runtime：一个组件的运行时登记信息与其全部实例 */
    static class Runtime {
        final String name;
        final Component callback;
        final List<Fiber> fibers = new ArrayList<>();

        Runtime(String name, Component callback) {
            this.name = name;
            this.callback = callback;
        }
    }

    private final Context ctx;
    /** 组件 -> 运行时（对应 _internal：Map&lt;Function, Component.Runtime&gt;） */
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
