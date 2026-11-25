package cordis.core;

/**
 * 对应 packages/core/src/events.ts 的 Hook：
 * 一个带注册方上下文的监听器条目。
 *
 * 除了 callback 本身，还记录了注册时的上下文（对应 hook.ctx）——仅用于
 * 调试/日志（谁注册的），不参与派发过滤：与 TS 一致，isolate 只作用于
 * 服务解析（见 Service.applicable），事件是全树广播的。
 *
 * 解绑由注册方 fiber 的 effect 负责（EventsService.on 把注册/解绑整体注册
 * 成 caller.fiber() 的 effect），因此这里不需要额外的 owner 校验。
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
