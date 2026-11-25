package cordis.agent;

import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;

/**
 * 会话编排插件：把已有的 {@code agent} 与 {@code memory} 服务组合成
 * {@link ConversationService}。
 *
 * <p>依赖通过 inject 声明，装配顺序因此无关紧要——两个前置服务只要最终
 * 就位，本插件就会自动挂载；任一被卸载，本插件跟着停用（fiber 回到
 * PENDING），不会留下半个可用状态。
 *
 * <pre>
 *   root.registry.use(ConversationPlugin.INSTANCE, Maps.of(),
 *           Maps.of("agent", true, "memory", true));
 * </pre>
 */
public final class ConversationPlugin implements Component {

    public static final ConversationPlugin INSTANCE = new ConversationPlugin();

    private ConversationPlugin() {
    }

    @Override
    public String getName() {
        return "conversation";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        AgentService agent = ctx.get("agent");
        MemoryService memory = ctx.get("memory");
        if (agent == null || memory == null) {
            throw new IllegalStateException("conversation requires \"agent\" and \"memory\"; "
                    + "declare inject {\"agent\": true, \"memory\": true} when use()ing it");
        }
        new ConversationService(ctx, agent, memory);
        return () -> {
        };
    }

}
