package cordis.core;

import java.util.Objects;

/**
 * 对应 packages/core/src/service.ts。
 *
 * 服务是“注册到上下文上的具名单例”：
 * - 构造时自动通过 ctx.provide() 注册
 *   （对应 TS 中构造函数里的 {@code self.ctx.reflect.provide(name, self)}）；
 * - 子类可以覆写 {@link #applicable} 决定哪些上下文能看见本服务
 *   （对应 TS 中 Service 的 [symbols.filter]）。
 *
 * 注意：Java 要求 super() 必须是构造器的第一条语句，因此子类的字段在
 * super() 返回时尚未初始化。本范例的服务都很简单所以不受影响；移植真实
 * 逻辑时应避免在构造器中对外发布带状态的字段。
 */
public abstract class Service {
    /** 服务名，同时也是它在上下文中的访问键 */
    public final String name;
    /** 服务所属的定义处上下文（def site，对应 this.ctx） */
    protected final Context ctx;

    protected Service(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
        ctx.provide(name, this);
    }

    /**
     * 对应 Service 的 [symbols.filter]：
     * 只有与本服务处于同一 isolate 视图的上下文才能访问它。
     * 默认实现比较两端沿父链解析出的隔离标签。
     */
    public boolean applicable(Context other) {
        return Objects.equals(ctx.isolateLabel(name), other.isolateLabel(name));
    }

    @Override
    public String toString() {
        return "Service(" + name + ")";
    }
}
