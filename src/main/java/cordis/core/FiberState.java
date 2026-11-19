package cordis.core;

/**
 * 对应 packages/core/src/fiber.ts 的 {@code const enum FiberState}。
 */
public enum FiberState {
    /** 已创建，依赖尚未就绪 */
    PENDING,
    /** 正在执行插件主体 */
    LOADING,
    /** 依赖全部就绪，插件正常运行 */
    ACTIVE,
    /** 插件执行抛出异常 */
    FAILED,
    /** 已被销毁 */
    DISPOSED,
    /** 正在逆序执行清理函数 */
    UNLOADING,
}
