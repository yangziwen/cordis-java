package cordis.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cordis.core.Context;
import cordis.core.Promise;
import cordis.core.Service;
import cordis.sqlite.SqliteService;

/**
 * 对话记忆服务（服务名 {@code memory}）：把 {@link LlmMessage} 持久化到
 * SQLite，按会话（session）组织。
 *
 * <h3>表结构</h3>
 *
 * <pre>
 *   sessions(id TEXT PK, created_at INTEGER, updated_at INTEGER)
 *   messages(id INTEGER PK AUTOINCREMENT, session_id TEXT, seq INTEGER,
 *            role TEXT, content TEXT, tool_calls TEXT, tool_call_id TEXT,
 *            qa INTEGER, created_at INTEGER, UNIQUE(session_id, seq))
 * </pre>
 *
 * <p>{@code tool_calls} 列存 {@link ToolCallCodec} 的转义分隔符格式——
 * SQLite 没有 JSON 类型，也不必为它引入 JSON 库。
 *
 * <h3>qa 列：问答主干 vs 工具过程</h3>
 *
 * <p>每行都全文落库（{@code content} 保留原始信息），但 {@code qa} 打了标记：
 * 1 = 问答主干（user 问题、不带工具调用的 assistant 终答）；0 = 工具过程
 * （带 {@code tool_calls} 的 assistant、tool 结果）。两种读法：
 *
 * <ul>
 *   <li>{@code load} —— 全量（档案视图，含工具过程）；</li>
 *   <li>{@code loadQa} —— 只取主干行。{@code AgentServer} 用它组装发给模型的
 *       历史：长会话只重发问答主干，工具返回的原始网页/文件内容不再每轮重发，
 *       token 负担不随轮次滚雪球；过程原文仍在库里，需要时可查。</li>
 * </ul>
 *
 * <h3>seq 的原子分配与写入事务</h3>
 *
 * <p>{@code seq} 由<b>单条</b> {@code INSERT ... SELECT COALESCE(MAX(seq),0)+1}
 * 在数据库侧分配：SQLite 的单语句本身就是原子的，并发追加不会拿到重复
 * seq，也不会互相覆盖。
 *
 * <p>而一轮对话的多条消息用 {@link SqliteService#transaction} 包起来——
 * "同时可见"不等于"各自原子"：半条轮次（有 assistant 的 tool_calls、没有
 * tool 结果）会让下次 load 得到一段非法历史。
 *
 * <h3>线程模型</h3>
 *
 * <p>JDBC 是阻塞的。所有公开方法返回 {@link Promise}，内部 {@code offload}
 * 到扩展池；{@code *Sync} 变体供已在线程池上的调用方直接复用（例如
 * {@link ConversationService} 在自己的 offload 里完成"读历史 → 跑 loop →
 * 写历史"），避免二次 offload 白占一个 worker。在<b>主循环</b>上调用
 * {@code *Sync} 会 fail-fast——那等于让整个调度器陪着 JDBC 一起停摆。
 */
public class MemoryService extends Service {

    /** 读历史时丢弃了尾部不完整的工具调用组：(sessionId, droppedCount) */
    public static final String EVT_REPAIRED = "memory/repaired";

    private static final String DDL_SESSIONS =
            "CREATE TABLE IF NOT EXISTS sessions ("
                    + "id TEXT PRIMARY KEY, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)";

    private static final String DDL_MESSAGES =
            "CREATE TABLE IF NOT EXISTS messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "session_id TEXT NOT NULL, "
                    + "seq INTEGER NOT NULL, "
                    + "role TEXT NOT NULL, "
                    + "content TEXT NOT NULL, "
                    + "tool_calls TEXT, "
                    + "tool_call_id TEXT, "
                    + "qa INTEGER NOT NULL DEFAULT 0, "
                    + "created_at INTEGER NOT NULL, "
                    + "UNIQUE(session_id, seq))";

    private static final String DDL_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq)";

    private static final String INSERT_MESSAGE =
            "INSERT INTO messages (session_id, seq, role, content, tool_calls, tool_call_id, qa, created_at) "
                    + "SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ?, ? "
                    + "FROM messages WHERE session_id = ?";

    private final SqliteService db;

    /**
     * 写操作互斥锁：底层是一条共享 JDBC 连接，transaction() 不支持并发
     * （两个事务在 auto-commit 开关上交错，后提交的会被打成
     * "database in auto-commit mode"），所以所有写路径在服务内排队执行。
     */
    private final Object writeLock = new Object();

    public MemoryService(Context ctx, SqliteService db) {
        super(ctx, "memory");
        this.db = db;
    }

    /**
     * 建表；由插件在扩展池上调用一次（幂等，IF NOT EXISTS）。
     *
     * <p>放在插件里而不是本类构造器里：Service 构造必须在主循环执行，
     * 而 DDL 是阻塞 JDBC，两者不能共存于同一个线程。表结构有变就直接
     * 删掉旧库文件重建，不做 ALTER 迁移。
     */
    public static void createSchema(SqliteService db) {
        db.execute(DDL_SESSIONS);
        db.execute(DDL_MESSAGES);
        db.execute(DDL_INDEX);
    }

    // ---------- 读 ----------

    /** 读取会话的全部消息（含工具过程，档案视图），按 seq 升序；不存在返回空列表 */
    public Promise<List<LlmMessage>> load(final String sessionId) {
        return ctx.scheduler.offload(() -> loadSync(sessionId));
    }

    public List<LlmMessage> loadSync(String sessionId) {
        assertNotMainLoop("MemoryService.loadSync");
        requireSessionId(sessionId);
        List<Map<String, Object>> rows = db.query(
                "SELECT role, content, tool_calls, tool_call_id FROM messages "
                        + "WHERE session_id = ? ORDER BY seq", sessionId);
        List<LlmMessage> history = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            history.add(LlmMessage.restore(
                    (String) row.get("role"),
                    (String) row.get("content"),
                    ToolCallCodec.decode((String) row.get("tool_calls")),
                    (String) row.get("tool_call_id")));
        }
        List<LlmMessage> repaired = repairTail(history);
        if (repaired.size() != history.size()) {
            ctx.events.emit(EVT_REPAIRED, sessionId, history.size() - repaired.size());
        }
        return repaired;
    }

    /**
     * 丢掉尾部不完整的工具调用组——这是"读侧修复"，不改库里的数据。
     *
     * <p>为什么必须有：OpenAI 协议要求每条 {@code role:"tool"} 消息紧跟声明了
     * 对应 {@code tool_call_id} 的 assistant 消息。如果库里存了一条 assistant
     * 的 {@code tool_calls} 却没存齐它的结果（历史遗留数据、外部直接写库、
     * 早期没有事务的版本写了一半），把这段历史原样送给模型会被网关直接 400，
     * 而且这个会话从此永久不可用。截在第一条不完整的位置，至少让会话还能继续。
     *
     * <p>只有<b>尾部</b>可能不完整：中间的缺口意味着这一轮之后还有对话，那是
     * 脏数据，但不在本方法的职责范围内——按同样规则在中途截断反而会丢掉
     * 后面所有正常消息。
     *
     * <p>返回的列表可变（调用方 AgentService 会往上追加）。
     */
    private static List<LlmMessage> repairTail(List<LlmMessage> history) {
        for (int i = 0; i < history.size(); i++) {
            LlmMessage message = history.get(i);
            if (!"assistant".equals(message.role) || message.toolCalls.isEmpty()) {
                continue;
            }
            if (answered(history, i, message.toolCalls)) {
                continue;
            }
            return new ArrayList<>(history.subList(0, i));
        }
        return history;
    }

    /** 紧跟在第 index 条之后的 tool 消息是否把这批调用全部配对上了 */
    private static boolean answered(List<LlmMessage> history, int index, List<ToolCall> calls) {
        Set<String> pending = new HashSet<>();
        for (ToolCall call : calls) {
            pending.add(call.id);
        }
        for (int j = index + 1; j < history.size(); j++) {
            LlmMessage next = history.get(j);
            if (!"tool".equals(next.role)) {
                break;
            }
            pending.remove(next.toolCallId);
        }
        return pending.isEmpty();
    }

    /** 会话列表，最近更新的在前 */
    public Promise<List<SessionInfo>> sessions() {
        return ctx.scheduler.offload(this::sessionsSync);
    }

    public List<SessionInfo> sessionsSync() {
        assertNotMainLoop("MemoryService.sessionsSync");
        List<Map<String, Object>> rows = db.query(
                "SELECT s.id, s.created_at, s.updated_at, COUNT(m.id) AS message_count "
                        + "FROM sessions s LEFT JOIN messages m ON m.session_id = s.id "
                        + "GROUP BY s.id ORDER BY s.updated_at DESC");
        List<SessionInfo> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(new SessionInfo(
                    (String) row.get("id"),
                    asLong(row.get("created_at")),
                    asLong(row.get("updated_at")),
                    (int) asLong(row.get("message_count"))));
        }
        return list;
    }

    /** 读取会话的问答主干（qa=1：用户问题 + 模型终答），按 seq 升序 */
    public Promise<List<LlmMessage>> loadQa(final String sessionId) {
        return ctx.scheduler.offload(() -> loadQaSync(sessionId));
    }

    /**
     * 问答主干视图——{@code AgentServer} 用它组装发给模型的历史：
     * 工具调用的原始过程留在库里（content 全文），不再每轮重发。
     * 主干行天然不带 tool_calls，无需尾部修复。
     */
    public List<LlmMessage> loadQaSync(String sessionId) {
        assertNotMainLoop("MemoryService.loadQaSync");
        requireSessionId(sessionId);
        List<Map<String, Object>> rows = db.query(
                "SELECT role, content, tool_calls, tool_call_id FROM messages "
                        + "WHERE session_id = ? AND qa = 1 ORDER BY seq", sessionId);
        List<LlmMessage> history = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            history.add(LlmMessage.restore(
                    (String) row.get("role"),
                    (String) row.get("content"),
                    ToolCallCodec.decode((String) row.get("tool_calls")),
                    (String) row.get("tool_call_id")));
        }
        return history;
    }

    // ---------- 写 ----------

    public Promise<Void> append(final String sessionId, final LlmMessage message) {
        return appendAll(sessionId, Collections.singletonList(message));
    }

    public Promise<Void> appendAll(final String sessionId, final List<LlmMessage> messages) {
        return ctx.scheduler.offload(() -> {
            appendAllSync(sessionId, messages);
            return null;
        });
    }

    /**
     * 追加一批消息（全文，含工具过程；qa 标由 {@link #isQa} 判定）；会话不存在时自动创建。
     *
     * <p>seq 由数据库侧分配（见类注释）；并发调用在 {@link #writeLock} 上排队，
     * 两个会话同时追加只会得到不同的 seq，不会互相覆盖。
     */
    public void appendAllSync(String sessionId, List<LlmMessage> messages) {
        assertNotMainLoop("MemoryService.appendAllSync");
        requireSessionId(sessionId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        // 一轮对话的若干条消息必须同时可见：写一半留下的"assistant 声明了
        // tool_calls 却没有 tool 结果"会让下次 load 拿到一轮非法历史
        synchronized (writeLock) {
            db.transaction(() -> {
                db.execute("INSERT OR IGNORE INTO sessions (id, created_at, updated_at) VALUES (?, ?, ?)",
                        sessionId, now, now);
                for (LlmMessage message : messages) {
                    db.execute(INSERT_MESSAGE,
                            sessionId,
                            message.role,
                            message.content,
                            ToolCallCodec.encode(message.toolCalls),
                            message.toolCallId,
                            isQa(message) ? 1 : 0,
                            now,
                            sessionId);
                }
                db.execute("UPDATE sessions SET updated_at = ? WHERE id = ?", now, sessionId);
            });
        }
    }

    /** 删除会话及其全部消息 */
    public Promise<Void> delete(final String sessionId) {
        return ctx.scheduler.offload(() -> {
            deleteSync(sessionId);
            return null;
        });
    }

    public void deleteSync(String sessionId) {
        assertNotMainLoop("MemoryService.deleteSync");
        requireSessionId(sessionId);
        synchronized (writeLock) {
            db.execute("DELETE FROM messages WHERE session_id = ?", sessionId);
            db.execute("DELETE FROM sessions WHERE id = ?", sessionId);
        }
    }

    // ---------- 内部 ----------

    /**
     * 一条消息是否属于问答主干：user 问题，或不含工具调用的 assistant 终答。
     * 工具过程（带 tool_calls 的 assistant、tool 结果）不算——它们落库存档，
     * 但不进入发给模型的历史。
     */
    public static boolean isQa(LlmMessage message) {
        if ("user".equals(message.role)) {
            return true;
        }
        return "assistant".equals(message.role) && message.toolCalls.isEmpty();
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
    }

    private void assertNotMainLoop(String operation) {
        if (Thread.currentThread() == ctx.scheduler.mainLoopThread()) {
            throw new IllegalStateException(operation + " performs blocking JDBC and must not run"
                    + " on the main loop; use the Promise-returning variant instead");
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @Override
    public String toString() {
        return "MemoryService(" + (db == null ? "-" : db.getUrl()) + ")";
    }

}
