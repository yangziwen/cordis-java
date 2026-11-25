package cordis.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 *            ├─ 无 toolCalls → emit(reply) → AgentResult(ANSWERED)
 *            └─ 并发执行本轮全部工具 → 结果回填历史 → 下一轮
 * </pre>
 *
 * <h3>线程模型（本类最重要的设计约束）</h3>
 *
 * <p>整个 loop 通过 {@code scheduler.offload} 跑在扩展池 worker 上，模型调用
 * 与工具执行都在那里同步进行；主循环从头到尾不被触碰。对外通知走
 * {@code ctx.events.emit(...)}——emit 内部自带 {@code scheduler.execute}，
 * 从 worker 调用是安全的，监听器照常在主循环上串行执行。
 *
 * <p>工具并发用的是本类<b>自带的</b>小线程池（{@code toolPool}），不是
 * {@code scheduler.offload}：本类自己就占着扩展池的一个 worker，从 worker
 * 再 offload 并等它回来，在整池都在等子任务的情况下会自锁。工具池里的
 * 线程是 daemon，由 {@link AgentPlugin} 在 fiber 卸载时关掉。
 *
 * <h3>事件</h3>
 *
 * <ul>
 *   <li>{@code agent/step}：(step, toolCount) 每轮开始；</li>
 *   <li>{@code agent/reply}：(content) 模型终答；</li>
 *   <li>{@code agent/tool}：(toolName, result) 每次工具执行完毕（同一轮的多个
 *       工具是并发的，但事件仍按请求顺序发出）。</li>
 * </ul>
 *
 * <h3>错误策略</h3>
 *
 * <ul>
 *   <li>模型调用失败：整个 run rejected（网络问题重试一轮没意义，交给调用方）；</li>
 *   <li>工具执行失败 / 工具不存在：作为结果文本回填历史，模型可见、可改道，
 *       loop 不中断——“工具坏了”是 agent 的常态而非事故。回填文本由
 *       {@link ToolError} 拼成合法的 {@code {"error":"..."}}，异常消息里的
 *       引号与换行不会污染协议。</li>
 * </ul>
 *
 * <h3>结束方式</h3>
 *
 * <p>返回 {@link AgentResult}，{@code stopReason} 区分"答完了"和"打满轮数"。
 * 早先的实现把后者拼成 {@code "(max steps reached: N)"} 当正常答案返回，
 * 调用方分不清是模型说的还是框架说的。
 */
public class AgentService extends Service {

    public static final String EVT_STEP = "agent/step";

    public static final String EVT_REPLY = "agent/reply";

    public static final String EVT_TOOL = "agent/tool";

    private final LlmClient llm;

    private final ToolService tools;

    private final int maxSteps;

    /** 每轮求值的 system 指令；可空（为 null 的轮次不带 system 消息） */
    private final SystemProvider system;

    /** 同轮多工具的并发池；理由见类注释的线程模型 */
    private final ExecutorService toolPool;

    public AgentService(Context ctx, LlmClient llm, ToolService tools, int maxSteps,
                        SystemProvider system) {
        super(ctx, "agent");
        this.llm = llm;
        this.tools = tools;
        this.maxSteps = maxSteps;
        this.system = system;
        this.toolPool = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "agent-tool");
            thread.setDaemon(true);
            return thread;
        });
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
    AgentResult runBlocking(List<LlmMessage> history, String input, AgentCall call)
            throws Exception {
        history.add(LlmMessage.user(input));
        int toolCallCount = 0;
        for (int step = 1; step <= maxSteps; step++) {
            if (call.isCancelled()) {
                throw new CancellationException("agent run cancelled");
            }
            List<Tool> available = tools.snapshot();
            ctx.events.emit(EVT_STEP, step, available.size());
            // system 每轮重新求值：skill 清单等动态段可能在上一轮之后被 reload 刷过
            LlmReply reply = llm.chat(history, available, system == null ? null : system.provide());
            history.add(LlmMessage.assistant(reply));
            if (!reply.hasToolCalls()) {
                ctx.events.emit(EVT_REPLY, reply.content);
                return new AgentResult(reply.content, AgentResult.StopReason.ANSWERED,
                        step, toolCallCount);
            }
            // 模型一轮请求多个工具时并发执行；结果仍按请求顺序回填，
            // 保证历史里 tool 消息的顺序与 assistant.tool_calls 一致
            List<String> results = dispatchAll(available, reply.toolCalls);
            for (int i = 0; i < reply.toolCalls.size(); i++) {
                ToolCall request = reply.toolCalls.get(i);
                String result = results.get(i);
                history.add(LlmMessage.toolResult(request.id, result));
                toolCallCount++;
                ctx.events.emit(EVT_TOOL, request.name, result);
            }
        }
        return new AgentResult("", AgentResult.StopReason.MAX_STEPS, maxSteps, toolCallCount);
    }

    /** 并发执行本轮全部工具，返回与入参同序的结果 */
    private List<String> dispatchAll(List<Tool> available, List<ToolCall> requests)
            throws Exception {
        List<Future<String>> futures = new ArrayList<>(requests.size());
        for (final ToolCall request : requests) {
            futures.add(toolPool.submit(() -> dispatch(available, request)));
        }
        List<String> results = new ArrayList<>(requests.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                // dispatch 已把 Exception 转成回填文本，能到这里的是 Error 级别
                results.add(ToolError.of("tool " + requests.get(i).name
                        + " crashed: " + e.getCause()));
            }
        }
        return results;
    }

    /** 执行一次工具调用；失败回填错误文本而不是抛出，让模型自行改道 */
    private String dispatch(List<Tool> available, ToolCall request) {
        for (Tool tool : available) {
            if (tool.name().equals(request.name)) {
                try {
                    return tool.run(request.arguments);
                } catch (Exception e) {
                    return ToolError.of("tool " + request.name + " failed: " + e);
                }
            }
        }
        // 工具在模型请求与执行之间被热卸载：同样作为可恢复信息回填
        return ToolError.of("unknown tool: " + request.name);
    }

    /** 由 {@link AgentPlugin} 的清理函数调用：停掉工具并发池 */
    void shutdown() {
        toolPool.shutdownNow();
    }

    @Override
    public String toString() {
        return "AgentService(maxSteps=" + maxSteps + ")";
    }

}
