package cordis.sqlite;

import cordis.core.Context;
import cordis.core.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite 服务：一个真实的 Service 子类用例。
 *
 * - 构造时通过 super(ctx, "sqlite") 自动注册（对应 Context.provide → provisions）；
 * - 持有 JDBC 连接，对外提供参数化的 execute / query / queryOne；
 * - close() 由插件返回的清理函数调用——fiber 卸载时连接随之关闭
 *   （插件把 close offload 到扩展池，不在主循环上做阻塞 IO）。
 */
public class SqliteService extends Service {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteService.class);

    private final String url;

    private final Connection connection;

    /**
     * 已有连接的构造：供异步插件使用“扩展池建连 → 主循环登记服务”的两段式
     * 初始化（见 SqlitePlugin.applyAsync）。注意 ctx.provide 在本构造中执行，
     * 必须发生在主循环线程上。
     *
     * 这里不再提供“构造器里自行 DriverManager.getConnection”的重载：那会在
     * 调用方线程（可能是主循环）上做阻塞 IO，是一条人人可踩的陷阱路径。
     * 建连请一律走 ctx.scheduler.offload。
     */
    public SqliteService(Context ctx, String url, Connection connection) {
        super(ctx, "sqlite");
        this.url = url;
        this.connection = connection;
    }

    public String getUrl() {
        return url;
    }

    /** DDL / DML（CREATE / INSERT / UPDATE / DELETE），返回受影响行数 */
    public int execute(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("sqlite execute failed: " + sql, e);
        }
    }

    /** 参数化查询，返回行列表（LinkedHashMap 保持列顺序） */
    public List<Map<String, Object>> query(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("sqlite query failed: " + sql, e);
        }
    }

    /** 查询单行，无结果返回 null */
    public Map<String, Object> queryOne(String sql, Object... params) {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 连通性自检（SELECT 1），由插件在 apply 中调用以尽早暴露加载失败 */
    public void ping() {
        queryOne("SELECT 1");
    }

    /**
     * 在一个事务里执行一组操作：正常返回则提交，抛出 RuntimeException 则整体
     * 回滚并把原异常抛出去。
     *
     * <p>为什么需要它：{@link #execute} 每次都是独立的隐式事务（auto-commit），
     * 所以"一次逻辑操作里的多条 INSERT"要么全成、要么全不成这件事，只能由
     * 调用方在这里显式表达。典型场景是落一条对话轮次——写到一半失败留下的
     * 半条记录比一条都没写更糟。
     *
     * <p><b>不可嵌套</b>：JDBC 没有嵌套事务（SQLite 的 SAVEPOINT 能模拟，
     * 本项目用不到）。<b>不做并发保护</b>：连接是共享单例，同一个 Connection
     * 上的并发事务本来就没有独立语义，调用方需自行保证同一时刻只有一个事务
     * （目前的调用点都在单个扩展池任务里）。
     */
    public void transaction(Runnable work) {
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("sqlite begin transaction failed", e);
        }
        try {
            work.run();
            connection.commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("sqlite commit failed", e);
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException e) {
                LOG.warn("restore auto-commit failed", e);
            }
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.warn("rollback failed", e);
        }
    }

    /** 关闭连接：由插件返回的清理函数调用（fiber 卸载时触发） */
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("error while closing", e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    @Override
    public String toString() {
        return "SqliteService(" + url + ")";
    }

}
