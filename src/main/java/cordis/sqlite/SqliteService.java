package cordis.sqlite;

import cordis.core.Context;
import cordis.core.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 服务：一个真实的 Service 子类用例。
 *
 * - 构造时通过 super(ctx, "sqlite") 自动注册（对应 Context.provide → provisions）；
 * - 持有 JDBC 连接，对外提供参数化的 execute / query / queryOne；
 * - close() 由插件返回的清理函数调用——fiber 卸载时连接随之关闭。
 */
public class SqliteService extends Service {

    private final String url;
    private final Connection connection;

    public SqliteService(Context ctx, String url) {
        super(ctx, "sqlite");
        this.url = url;
        try {
            this.connection = DriverManager.getConnection(url);
        } catch (SQLException e) {
            // 对应插件加载失败：异常会被 Fiber.reload 捕获，fiber 进入 FAILED
            throw new IllegalStateException("cannot open sqlite connection: " + url, e);
        }
    }

    /**
     * 已有连接的构造：供异步插件使用“扩展池建连 → 主循环登记服务”的两段式
     * 初始化（见 SqlitePlugin.applyAsync）。注意 ctx.provide 在本构造中执行，
     * 必须发生在主循环线程上。
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

    /** 关闭连接：由插件返回的清理函数调用（fiber 卸载时触发） */
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("[sqlite] error while closing: " + e);
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
