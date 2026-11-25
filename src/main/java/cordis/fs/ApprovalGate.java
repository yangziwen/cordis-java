package cordis.fs;

/**
 * 写操作（write / edit）之前的审批关口：能力层不知道「谁在写、为什么写」，
 * 只知道「这次写要动哪个文件、写多大、长什么样」，把决定权交回给装配方。
 *
 * <p>默认<b>拒绝一切</b>（deny-all）。文件系统是少数几个「写错就真的改了
 * 用户数据」的能力，宁可让装配方显式放开，也不给一个静默放行的默认值。
 *
 * <p>不是 agent 的概念：一个定时任务、一条 HTTP 接口同样应该经过它。
 */
@FunctionalInterface
public interface ApprovalGate {

    /**
     * @return true 才允许本次写入；false 或抛出异常都视为拒绝
     */
    boolean approve(WriteRequest request);

    /** 拒绝一切写入的关口；也是未配置时的默认值 */
    static ApprovalGate denyAll() {
        return request -> false;
    }

}
