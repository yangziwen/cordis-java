package cordis.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对应 packages/core/src/context.ts + reflect.ts 的服务解析部分。
 *
 * TS 版本通过 Proxy 拦截属性访问来“隐式”解析服务（写 {@code ctx.foo} 即可）；
 * Java 没有等价的元编程能力，这里改用显式的 {@link #get}，但核心机制一一对应：
 *
 * - 上下文构成一棵 extend 树（对应 Object.create 原型链），
 *   isolate / intercept 表沿父链做影子查找；
 * - 服务仓库是全局共享的（对应 ReflectService.store，它只挂在根上下文上），
 *   以“隔离标签”为主键。第一次 provide 时在根上下文登记默认标签
 *   （对应 {@code root[symbols.isolate][name] ??= Symbol(name)}），
 *   因此默认情况下兄弟插件可以互相解析服务；
 * - {@link #isolate} 为某个服务名生成全新标签并遮蔽父链查找：隔离分支中
 *   provide/get 走的都是这个新标签，于是和外界的同名服务互不可见
 *   （对应 ctx.isolate(name) + Service 的 [symbols.filter]）；
 * - {@link #intercept} 覆盖某个服务在本分支中的配置（对应 [symbols.intercept]）。
 */
public class Context {
    /** 父上下文（extend 链），根上下文为 null */
    public final Context parent;
    /** 根上下文：服务仓库、registry、events 都由它持有并全树共享 */
    public final Context root;

    /** 本层的隔离标签：服务名 -> 标签（对应 [symbols.isolate]） */
    private final Map<String, Object> isolate = new LinkedHashMap<>();
    /** 本层的拦截配置：服务名 -> 配置（对应 [symbols.intercept]） */
    private final Map<String, Object> intercept = new LinkedHashMap<>();

    /** 组件注册表（共享，对应 RegistryService 挂在根上下文上） */
    public final Registry registry;
    /** 事件系统（共享，对应 EventsService 挂在根上下文上） */
    public final EventsService events;
    /**
     * 调度中枢：主循环 + 扩展阻塞池（挂在根上下文，全树共享）。
     * 所有生命周期状态迁移与事件派发都在主循环上串行执行；
     * 阻塞型工作经 scheduler.offload(...) 到扩展池，结果投递回主循环。
     */
    public final Scheduler scheduler;

    /**
     * 全局服务仓库：隔离标签 -> 服务实例。
     * 对应 ReflectService.store（以 isolate symbol 为主键）。
     */
    final Map<Object, Service> serviceStore = new LinkedHashMap<>();

    /** 本上下文当前对应的 fiber（对应 ctx.fiber） */
    private Fiber fiber;

    private Context(Context parent) {
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        if (parent == null) {
            // scheduler / registry / events 只在根上下文创建一次，派生上下文继承共享
            this.scheduler = new Scheduler();
            this.registry = new Registry(this);
            this.events = new EventsService(scheduler);
            // 根上下文自带一个“空插件”的 fiber
            // （对应 Context 构造函数中的 new Fiber(self, {}, ..., null, ...)）
            this.fiber = new Fiber(this);
        } else {
            this.scheduler = parent.scheduler;
            this.registry = parent.registry;
            this.events = parent.events;
        }
    }

    public static Context root() {
        return new Context(null);
    }

    /**
     * 本上下文当前对应的 fiber（对应 ctx.fiber）。
     * 与 TS 的原型链解析一致：裸派生上下文（extend/isolate/intercept 产生、
     * 尚未在上面 use() 过插件）没有自己的 fiber，沿 parent 链解析到最近的
     * 祖先 fiber——效果、服务的 provisions 都归属祖先，最终篏底到根上下文
     * 自带的 fiber，因此正常情况下永不为 null。
     */
    public Fiber fiber() {
        for (Context c = this; c != null; c = c.parent) {
            if (c.fiber != null) return c.fiber;
        }
        return null; // 理论上不可达：根上下文构造时必有 fiber
    }

    void setFiber(Fiber fiber) {
        this.fiber = fiber;
    }

    /** 对应 ctx.extend()：派生一个子上下文（isolate/intercept 走父链影子查找） */
    public Context extend() {
        return new Context(this);
    }

    /** 对应 ctx.isolate(name)：为指定服务生成全新的隔离标签并遮蔽父链 */
    public Context isolate(String name) {
        Context child = extend();
        child.isolate.put(name, new Object());
        return child;
    }

    /** 对应 ctx.intercept(name, config)：在本分支覆盖服务的配置 */
    public Context intercept(String name, Object config) {
        Context child = extend();
        child.intercept.put(name, config);
        return child;
    }

    /**
     * 对应 ctx.events.on()：在共享的 EventsService 上注册监听器，
     * 但以“本上下文所在的 fiber”为 effect 宿主（对应 Proxy 绑定的 thisArg）。
     */
    public Disposable on(String name, EventListener listener) {
        return events.on(this, name, listener);
    }

    /** 对应 ctx.events.once() */
    public Disposable once(String name, EventListener listener) {
        return events.once(this, name, listener);
    }

    /**
     * 服务注册，对应 ReflectService.provide()：
     * - 先按本分支的 isolate 表确定标签；没有则给根上下文登记一个默认标签；
     * - 把服务放入全局仓库；
     * - 记录到当前 fiber 的 provisions（fiber 卸载时服务随之撤销）；
     * - 若提供者已经 ACTIVE，通知等待该服务的 fiber（对应 reflect.notify）。
     */
    void provide(String name, Service service) {
        Object key = isolateLabel(name);
        if (key == null) {
            key = new Object();
            root.isolate.put(name, key); // 对应 root[symbols.isolate][name] ??= Symbol(name)
        }
        // 对应 reflect.ts provide 的冲突检查：同一隔离视图中同名服务必须唯一，
        // 否则 ctx.get(name) 的结果将不确定。注意这里不能只 putIfAbsent 静默保先：
        // 后注册的 fiber 会误以为服务可见，错误应 fail-fast 抛在发生点上。
        // （需要多实例时应使用 isolate 产生不同标签，而非共用同一标签。）
        Service prev = root.serviceStore.putIfAbsent(key, service);
        if (prev != null) {
            throw new IllegalStateException("service \"" + name
                    + "\" has been registered at <" + prev.ctx.fiber().name + ">");
        }
        // 裸上下文上构造的服务沿父链归属到祖先 fiber 的 provisions
        // （对应 TS 原型链解析出的 ctx.fiber）
        Fiber current = fiber();
        if (current != null) current.provisions.add(service);
        if (current == null || current.state == FiberState.ACTIVE) {
            registry.onServiceChanged(name);
        }
    }

    /** 服务撤销，对应 provide 返回的清理函数中删除 store 条目 */
    void unprovide(String name, Service service) {
        Object key = isolateLabel(name);
        if (key != null && root.serviceStore.get(key) == service) {
            root.serviceStore.remove(key);
        }
        registry.onServiceChanged(name);
    }

    /**
     * 服务解析，对应 ReflectService.get() / Proxy get：
     * 按本分支的隔离标签查全局仓库；提供者 fiber 必须处于 ACTIVE
     * （对应 _getImpl 的 strict 检查）。
     */
    @SuppressWarnings("unchecked")
    public <T extends Service> T get(String name) {
        Object key = isolateLabel(name);
        if (key == null) return null;
        Service s = root.serviceStore.get(key);
        if (s == null) return null;
        if (s.ctx.fiber().state != FiberState.ACTIVE) return null;
        return (T) s;
    }

    public boolean has(String name) {
        return get(name) != null;
    }

    /** 沿父链查找服务名的隔离标签，对应 [symbols.isolate] 的原型链查找 */
    Object isolateLabel(String name) {
        for (Context c = this; c != null; c = c.parent) {
            if (c.isolate.containsKey(name)) return c.isolate.get(name);
        }
        return null;
    }

    /** 沿父链查找拦截配置，对应 [symbols.intercept] 的原型链查找 */
    public Object interceptConfig(String name) {
        for (Context c = this; c != null; c = c.parent) {
            if (c.intercept.containsKey(name)) return c.intercept.get(name);
        }
        return null;
    }
}
