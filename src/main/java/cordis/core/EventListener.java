package cordis.core;

/**
 * 对应 packages/core/src/events.ts 中监听器的函数类型
 * （{@code (...args: any[]) => any}）：可变参数的事件监听器。
 *
 * 返回值语义由派发模式决定：
 * - emit / parallel：返回值被忽略；
 * - serial / bail：返回非 null 即视为“截获”，后续监听器不再执行；
 * - waterfall：返回值继续向下一个监听器传递（中间件模型）。
 */
@FunctionalInterface
public interface EventListener {

    Object invoke(Object... args);

}
