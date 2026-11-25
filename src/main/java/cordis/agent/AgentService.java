package cordis.agent;

import java.util.List;
import java.util.concurrent.CancellationException;

import cordis.core.Context;
import cordis.core.Service;

/**
 * agent loop 本体（服务名 {@code agent}）。
 *
 * <p>循环结构：
 *
 * <pre>
 *   user input
 *     └─ for step in 1..maxSteps:
 *          工具快照 → llm.chat(历史, 快照)
 *            ├─ 无 toolCalls → emit(reply) → 终答返回
 *            └─ 逐个执行工具 → 结果回填历史 → 下一轮
 * </pre>
 *
 * <h3>线程模型（本类最重要的设计约束）</h3>
 *
 * <p>整个 loop 通过 {@code scheduler.offload} 跑在扩展池 worker 上，模型调用
 * 与工具执行都在那里同步进行；主循环从头到尾不被触碰。对外通知走
 * {@code ctx.events.emit(...)}——emit 内部自带 {@code scheduler.execute}，
 * 从 worker 调用是安全的，监听器照常在主循环上串行执行。
 *
 * <h3>事件</h3>
 *
 * <ul>
 *   <li>{@code agent/step}：(step, toolCount) 每轮开始；</li>
 *   <li>{@code agent/reply}：(content) 模型终答；</li>
 *   <li>{@code agent/tool}：(toolName, result) 每次工具执行完毕。</li>
 * </ul>
 *
 * <h3>错误策略</h3>
 *
 * <ul>
 *   <li>模型调用失败：整个 run rejected（网络问题重试一轮没意义，交给调用方）；</li>
 *   <li>工具执行失败 / 工具不存在：作为结果文本回填历史，模型可见、可改道，
 *       loop 不中断——"工具坏了"是 agent 的常态而非事故。</li>
 * </ul>
 */
public class AgentService extends Service {

    public static final String EVT_STEP = "agent/step";

    public static final String EVT_REPLY = "agent/reply";

    public static final String EVT_TOOL = "agent/tool";

    private final LlmClient llm;

    private final ToolService tools;

    private final int maxSteps;

    public AgentService(Context ctx, LlmClient llm, ToolService tools, int maxSteps) {
        super(ctx, "agent");
        this.llm = llm;
        this.tools = tools;
        this.maxSteps = maxSteps;
    }

    /**
     * 跑一次对话。{@code history} 是调用方持有的会话记忆：本方法会向其中
     * 追加 user / assistant / tool 消息，run 结束后列表即完整对话——
     * 多轮对话 = 对同一个列表连续 run。
     */
    public AgentCall run(final List<LlmMessage> history, final String input) {
        final AgentCall call = new AgentCall(ctx.scheduler.defer());
        ctx.scheduler.offload(() -> {
            try {
                call.complete(runBlocking(history, input, call));
            } catch (Throwable t) {
                call.fail(t);
            }
            return null;
        });
        return call;
    }

    /**
     * loop 主体（同步，允许阻塞）。
     *
     * <p>包级私有：供 {@link ConversationService} 在自己的 offload 里
     * 把"读历史 → 跑 loop → 写历史"串成一个任务，避免在 worker 上再
     * offload 一次白占一个线程。调用方必须保证自己不在主循环上。
     */
    String runBlocking(List<LlmMessage> history, String input, AgentCall call) throws Exception {
        history.add(LlmMessage.user(input));
        for (int step = 1; step <= maxSteps; step++) {
            if (call.isCancelled()) {
                throw new CancellationException("agent run cancelled");
            }
            List<Tool> available = tools.snapshot();
            ctx.events.emit(EVT_STEP, step, available.size());
            LlmReply reply = llm.chat(history, available);
            history.add(LlmMessage.assistant(reply));
            if (!reply.hasToolCalls()) {
                ctx.events.emit(EVT_REPLY, reply.content);
                return reply.content;
            }
            for (ToolCall request : reply.toolCalls) {
                if (call.isCancelled()) {
                    throw new CancellationException("agent run cancelled");
                }
                String result = dispatch(available, request);
                history.add(LlmMessage.toolResult(request.id, result));
                ctx.events.emit(EVT_TOOL, request.name, result);
            }
        }
        return "(max steps reached: " + maxSteps + ")";
    }

    /** 执行一次工具调用；失败回填错误文本而不是抛出，让模型自行改道 */
    private String dispatch(List<Tool> available, ToolCall request) {
        for (Tool tool : available) {
            if (tool.name().equals(request.name)) {
                try {
                    return tool.run(request.arguments);
                } catch (Exception e) {
                    return "{\"error\":\"tool " + request.name + " failed: " + e + "\"}";
                }
            }
        }
        // 工具在模型请求与执行之间被热卸载：同样作为可恢复信息回填
        return "{\"error\":\"unknown tool: " + request.name + "\"}";
    }

    @Override
    public String toString() {
        return "AgentService(maxSteps=" + maxSteps + ")";
    }

}
