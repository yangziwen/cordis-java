package cordis.core;

/**
 * 对应 packages/core/src/fiber.ts 中的 {@code type Disposable<T> = () => T}。
 *
 * 在 cordis 中，任何“可撤销”的资源（事件监听、服务注册、effect 的清理逻辑）
 * 都用一个无参函数表示。Java 里用函数式接口承载同一概念。
 */
@FunctionalInterface
public interface Disposable {
    void dispose();
}
