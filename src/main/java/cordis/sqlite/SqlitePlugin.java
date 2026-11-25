package cordis.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Promise;

/**
 * SQLite 插件：Component 的真实用例（<b>异步初始化版</b>）。
 *
 * JDBC 建连是阻塞操作，不能放在主循环上。本插件覆写 {@link #applyAsync}，
 * 实现“扩展池干活、主循环登记”的两段式初始化：
 *
 * <ol>
 *   <li>阻塞的 {@code DriverManager.getConnection} 经 {@code offload} 投递到
 *       扩展池（cordis-worker）——期间 fiber 停在 LOADING，主循环零阻塞，
 *       其他插件照常挂载；</li>
 *   <li>建连完成回调回主循环，在 {@code then} 中构造 {@link SqliteService}
 *       ——ctx.provide（服务登记、依赖者唤醒）必须在主循环执行；</li>
 *   <li>ping 自检同样是 JDBC，再次 offload——失败则 fiber 进入 FAILED；</li>
 *   <li>返回清理函数，fiber 卸载时关闭 JDBC 连接。</li>
 * </ol>
 *
 * fiber 恢复（依赖消失后重新 reload）走同一条 applyAsync 路径，
 * 因此恢复过程同样是异步的。
 */
public class SqlitePlugin implements Component {

    public static final SqlitePlugin INSTANCE = new SqlitePlugin();

    private SqlitePlugin() {
    }

    /** 名字属于组件定义（对应 TS 的 component.name） */
    @Override
    public String getName() {
        return "sqlite";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        throw new UnsupportedOperationException("本插件只走 applyAsync（异步建连）");
    }

    @Override
    public Promise<Disposable> applyAsync(Context ctx, Map<String, Object> config) {
        String url = String.valueOf(config.getOrDefault("url", "jdbc:sqlite::memory:"));
        return ctx.scheduler.<Connection>offload(() -> DriverManager.getConnection(url))
                // 建连完成，回调回主循环：在这里构造 Service（provide 必须在主循环）
                .then(conn -> new SqliteService(ctx, url, conn))
                // ping 自检也是阻塞 JDBC → offload；失败则 fiber 进入 FAILED
                .thenAsync(service -> ctx.scheduler.offload(() -> {
                    service.ping();
                    System.out.println("[sqlite] connection opened: " + url
                            + "  (on " + Thread.currentThread().getName() + ")");
                    return service;
                }))
                // 返回清理函数：fiber 卸载时由 teardown 调用
                .then(service -> (Disposable) service::close);
    }
}
