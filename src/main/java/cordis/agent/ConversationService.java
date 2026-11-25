package cordis.agent;

import java.util.ArrayList;
import java.util.List;

import cordis.core.Context;
import cordis.core.Service;

/**
 * 会话编排（服务名 {@code conversation}）：把"读记忆 → 跑 loop → 写记忆"
 * 合成一次调用。
 *
 * <pre>
 *   AgentCall call = conversation.send("user-42", "我叫小明");
 * </pre>
 *
 * <p>三点设计：
 *
 * <ul>
 *   <li><b>一个 offload 装下三段</b>：load / loop / save 全在同一个 worker
 *       线程上顺序执行。若用 Promise 串联，loop 自己还要 offload 一次，
 *       一条对话会白占两个 worker；顺带也保证了"同一会话的两次 send 不会
 *       交错读写"——它们在同一个线程池里排队，但仍是并发执行，见下条；</li>
 *   <li><b>落库失败不影响答案</b>：答案已经产生，用户等的是它。写库失败
 *       通过 {@link #EVT_PERSIST_ERROR} 事件抛出（监听器可告警/重试），
 *       本次 completion 仍以答案结算；</li>
 *   <li><b>取消粒度仍是轮</b>：{@link AgentCall#dispose()} 后 loop 在轮间
 *       停止，但已经算出的部分消息仍会落库——保留已发生的事实，比丢弃
 *       半段对话更可控。</li>
 * </ul>
 *
 * <p><b>同一 session 的并发 send 未做串行化</b>：两条消息可能拿到交错递增
 * 的 seq。seq 本身由数据库原子分配（不会重复/覆盖），但对话语义会乱。
 * 需要严格串行时，调用方按 sessionId 加锁，或等本类的 mailbox 版本。
 */
public class ConversationService extends Service {

    /** 落库失败：(Throwable) 写库异常 */
    public static final String EVT_PERSIST_ERROR = "conversation/persist-error";

    private final AgentService agent;

    private final MemoryService memory;

    public ConversationService(Context ctx, AgentService agent, MemoryService memory) {
        super(ctx, "conversation");
        this.agent = agent;
        this.memory = memory;
    }

    /**
     * 在指定会话上发起一轮对话：加载全部历史，交给 agent loop，把本轮
     * 新增的消息写回存储。
     */
    public AgentCall send(final String sessionId, final String input) {
        final AgentCall call = new AgentCall(ctx.scheduler.defer());
        ctx.scheduler.offload(() -> {
            try {
                List<LlmMessage> history = memory.loadSync(sessionId);
                int before = history.size();
                AgentResult result = agent.runBlocking(history, input, call);
                List<LlmMessage> produced =
                        new ArrayList<>(history.subList(before, history.size()));
                try {
                    memory.appendAllSync(sessionId, produced);
                } catch (RuntimeException e) {
                    ctx.events.emit(EVT_PERSIST_ERROR, e);
                }
                call.complete(result);
            } catch (Throwable t) {
                call.fail(t);
            }
            return null;
        });
        return call;
    }

    @Override
    public String toString() {
        return "ConversationService(" + agent + ", " + memory + ")";
    }

}
