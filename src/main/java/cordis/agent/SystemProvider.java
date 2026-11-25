package cordis.agent;

/**
 * system 指令的提供者：每轮模型调用前求值一次。
 *
 * <p>设计成接口而不是 {@code String}，是因为 system 的动态段（典型：
 * skill 清单、可用工具的补充说明）会随热插拔变化——fiber reload 重扫
 * skill 目录之后，下一轮就应该看到新清单，而常量做不到。
 *
 * <p>与 {@link LlmClient#chat} 的契约分工：提供者负责“算出当前该下发
 * 什么”，实现方负责“怎么下发”（OpenAI 协议里是消息数组首条 system 消息）。
 * 因此 system 不进入 {@code history}，也就不会被会话持久化层当作记忆存下。
 *
 * <p>实现应保持轻量且不抛异常（抛出的异常会让整个 run rejected）；
 * 求值失败时返回 null 或退化文本是更合适的降级。
 */
@FunctionalInterface
public interface SystemProvider {

    /** 当前轮次的 system 指令全文；null 表示本轮不带 system 消息 */
    String provide();

}
