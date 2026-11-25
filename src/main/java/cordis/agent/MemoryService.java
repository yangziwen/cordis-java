package cordis.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
 *            created_at INTEGER, UNIQUE(session_id, seq))
 * </pre>
 *
 * <p>{@code tool_calls} 列存 {@link ToolCallCodec} 的转义分隔符格式——
 * SQLite 没有 JSON 类型，也不必为它引入 JSON 库。
 *
 * <h3>seq 的原子分配</h3>
 *
 * <p>{@link SqliteService} 没有暴露事务 API，本服务也不需要：每条消息用
 * <b>单条</b> {@code INSERT ... SELECT COALESCE(MAX(seq),0)+1} 落库，SQLite
 * 的单语句本身就是原子的，并发追加不会拿到重复 seq，也不会互相覆盖。
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
                    + "created_at INTEGER NOT NULL, "
                    + "UNIQUE(session_id, seq))";

    private static final String DDL_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq)";

    private static final String INSERT_MESSAGE =
            "INSERT INTO messages (session_id, seq, role, content, tool_calls, tool_call_id, created_at) "
                    + "SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ? "
                    + "FROM messages WHERE session_id = ?";

    private final SqliteService db;

    public MemoryService(Context ctx, SqliteService db) {
        super(ctx, "memory");
        this.db = db;
    }

    /**
     * 建表；由插件在扩展池上调用一次（幂等，IF NOT EXISTS）。
     *
     * <p>放在插件里而不是本类构造器里：Service 构造必须在主循环执行，
     * 而 DDL 是阻塞 JDBC，两者不能共存于同一个线程。
     */
    public static void createSchema(SqliteService db) {
        db.execute(DDL_SESSIONS);
        db.execute(DDL_MESSAGES);
        db.execute(DDL_INDEX);
    }

    // ---------- 读 ----------

    /** 读取会话的全部消息，按 seq 升序；不存在返回空列表 */
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
        return history;
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
     * 追加一批消息；会话不存在时自动创建。
     *
     * <p>seq 由数据库侧分配（见类注释），因此本方法的并发调用是安全的：
     * 两个线程同时追加只会得到不同的 seq，不会互相覆盖。
     */
    public void appendAllSync(String sessionId, List<LlmMessage> messages) {
        assertNotMainLoop("MemoryService.appendAllSync");
        requireSessionId(sessionId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        db.execute("INSERT OR IGNORE INTO sessions (id, created_at, updated_at) VALUES (?, ?, ?)",
                sessionId, now, now);
        for (LlmMessage message : messages) {
            db.execute(INSERT_MESSAGE,
                    sessionId,
                    message.role,
                    message.content,
                    ToolCallCodec.encode(message.toolCalls),
                    message.toolCallId,
                    now,
                    sessionId);
        }
        db.execute("UPDATE sessions SET updated_at = ? WHERE id = ?", now, sessionId);
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
        db.execute("DELETE FROM messages WHERE session_id = ?", sessionId);
        db.execute("DELETE FROM sessions WHERE id = ?", sessionId);
    }

    // ---------- 内部 ----------

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
