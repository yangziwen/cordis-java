package cordis.sqlite;

import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;

/**
 * SQLite 插件：Component 的真实用例。
 *
 * apply 中完成三件事（对应前面讨论过的 effect 语义）：
 * 1. 创建 SqliteService —— 构造即注册进全局服务仓库（provisions 归属本 fiber）；
 * 2. ping() 自检 —— 失败则 fiber 进入 FAILED，而非静默半加载；
 * 3. 返回清理函数 —— fiber 卸载时关闭 JDBC 连接（effect 的 teardown）。
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
        String url = String.valueOf(config.getOrDefault("url", "jdbc:sqlite::memory:"));
        SqliteService service = new SqliteService(ctx, url);
        service.ping();
        System.out.println("[sqlite] connection opened: " + url);
        return service::close;
    }
}
