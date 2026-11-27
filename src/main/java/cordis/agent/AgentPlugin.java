package cordis.agent;

import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;

/**
 * agent 插件：装配 {@link ToolService} + {@link AgentService}。
 *
 * <p>模型客户端从 config 注入（{@code "llm"}，{@link LlmClient} 实例，
 * 必填）——本插件只搭骨架，wire 协议属于 LlmClient 的实现，不放进框架层。
 *
 * <h3>配置项</h3>
 *
 * <ul>
 *   <li>{@code llm}（必填）模型客户端实现；</li>
 *   <li>{@code maxSteps}（默认 8）单次 run 的模型调用轮数上限——无此上限时，
 *       一个执着的模型可以无限请求工具；</li>
 *   <li>{@code system}（可选）system 指令：传 String 为固定基底，传
 *       {@link SystemProvider} 则每轮求值（动态段，如 skill 清单）；</li>
 *   <li>{@code statsEnabled}（默认 false）debug 观测：每次 llm.chat 前
 *       量历史文本，经 {@code AgentListener.onStats} 回调。</li>
 * </ul>
 *
 * <p>apply 返回的清理只做一件事：关掉 {@link AgentService} 的工具并发池
 * （本插件唯一的自有资源）。两个服务的注册由 fiber 的 provisions 机制自动
 * 撤销（Service 构造时已登记），无需在这里重复。
 */
public class AgentPlugin implements Component {

    public static final AgentPlugin INSTANCE = new AgentPlugin();

    private AgentPlugin() {
    }

    @Override
    public String getName() {
        return "agent";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        Object llm = config.get("llm");
        if (!(llm instanceof LlmClient)) {
            throw new IllegalArgumentException("AgentPlugin requires config \"llm\""
                    + " of type LlmClient (got: " + (llm == null ? "null" : llm.getClass()) + ")");
        }
int maxSteps = (int) number(config, "maxSteps", 8);
SystemProvider system = systemOf(config.get("system"));
boolean statsEnabled = Boolean.TRUE.equals(config.get("statsEnabled"));

// 同一 fiber 内直接传引用：两个服务同生共死，不存在解析时序问题
ToolService tools = new ToolService(ctx);
final AgentService agent = new AgentService(ctx, (LlmClient) llm, tools,
maxSteps, system, statsEnabled);
return () -> agent.shutdown();
    }

    /** String → 常量基底；SystemProvider → 直接使用；两者叠加，null → 无 system */
    private static SystemProvider systemOf(Object config) {
        if (config instanceof SystemProvider) {
            return (SystemProvider) config;
        }
        final String base = config instanceof String && !String.valueOf(config).trim().isEmpty()
                ? String.valueOf(config).trim() : null;
        if (base == null) {
            return null;
        }
        return () -> base;
    }

    private static long number(Map<String, Object> config, String key, long fallback) {
        Object v = config.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

}
