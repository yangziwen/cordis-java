package cordis.core;

/**
 * 对应 packages/core/src/events.ts 的 Hook：
 * 一个带注册方上下文的监听器条目。
 *
 * 除了 callback 本身，还记录了注册时的上下文（对应 hook.ctx）——TS 版本
 * 在派发时用它做 isolate 过滤；本范例的过滤已由 Service.applicable()
 * 承载，故 owner 仅保留备查。
 *
 * removed 标记对应 TS 的 hook.removed，用于派发时过滤已注销的监听器。
 *
 * 可见性：仅由 {@link EventsService} 创建和消费，字段不做 public 暴露。
 */
public class Hook {
    /** 注册监听器时的上下文（对应 hook.ctx） */
    final Context owner;
    final EventListener callback;
    boolean removed;

    Hook(Context owner, EventListener callback) {
        this.owner = owner;
        this.callback = callback;
    }
}
