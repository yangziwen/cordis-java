package cordis.demo;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.sqlite.SqlitePlugin;
import cordis.sqlite.SqliteService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite 服务的完整演示，重点展示 cordis 的依赖驱动生命周期：
 *
 * 1. 先加载数据访问组件（声明依赖 sqlite，但服务尚不存在 → PENDING）；
 * 2. 再加载 SQLite 插件（内存库）→ 服务注册 → 数据访问组件自动激活；
 * 3. 建表、参数化插入、查询；
 * 4. 事件触发查询（监听器是 fiber 上的 effect）；
 * 5. 卸载 SQLite → 依赖者自动卸载、连接关闭；
 * 6. 重新加载 SQLite → 依赖者自动复活。
 *
 * 运行方式：
 *   mvn compile
 *   java -cp "target/classes:$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.21.0.1/sqlite-jdbc-3.21.0.1.jar" \
 *        cordis.demo.SqliteDemo
 */
public class SqliteDemo {

    /** 数据访问组件：声明依赖 sqlite（声明在 use() 的 inject 参数里，见 main） */
    static final Component USER_REPO = (ctx, config) -> {
        SqliteService db = ctx.get("sqlite");
        System.out.println("[user-repo] started with " + db);

        // 监听器是 repo fiber 的 effect：repo 卸载时自动解绑，重载时重新注册
        ctx.on("user-created", args -> {
            Map<String, Object> row = db.queryOne(
                    "SELECT id, name, age FROM users WHERE id = ?", args[0]);
            System.out.println("[user-repo] user-created -> " + row);
            return null;
        });
        return null;
    };

    /** Java 8 兼容的 map 构造辅助，允许 null 值 */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] argv) {
        Context root = Context.root();

        // 1. 先加载数据访问组件：sqlite 服务尚不存在 → 停在 PENDING
        Fiber repoFiber = root.registry.use("user-repo", USER_REPO,
                map(), map("sqlite", null));
        System.out.println("repoFiber = " + repoFiber);

        // 2. 加载 SQLite 插件（内存库）→ 服务注册 → repo 自动激活
        Fiber dbFiber = root.registry.use("sqlite", SqlitePlugin.INSTANCE,
                map("url", "jdbc:sqlite::memory:"), map());
        System.out.println("dbFiber   = " + dbFiber);
        System.out.println("repoFiber = " + repoFiber + "\n");

        SqliteService db = root.get("sqlite");

        // 3. 建表 + 参数化插入 + 查询
        db.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)");
        db.execute("INSERT INTO users (name, age) VALUES (?, ?)", "alice", 30);
        db.execute("INSERT INTO users (name, age) VALUES (?, ?)", "bob", 25);
        db.execute("INSERT INTO users (name, age) VALUES (?, ?)", "carol", 35);
        System.out.println("-- users --");
        for (Map<String, Object> row : db.query("SELECT id, name, age FROM users ORDER BY id")) {
            System.out.println(row);
        }
        System.out.println("queryOne(age > 28) = "
                + db.queryOne("SELECT name FROM users WHERE age > ?", 28));
        System.out.println("count = "
                + db.queryOne("SELECT COUNT(*) AS total FROM users").get("total"));

        // 4. 事件触发查询
        System.out.println("\n-- emit(\"user-created\") --");
        root.events.emit("user-created", 2);

        // 5. 卸载 SQLite：依赖者自动卸载，连接关闭
        System.out.println("\n-- dbFiber.dispose() --");
        dbFiber.dispose();
        System.out.println("dbFiber   = " + dbFiber);
        System.out.println("repoFiber = " + repoFiber);
        System.out.println("root.has(\"sqlite\") = " + root.has("sqlite"));

        // 6. 重新加载：repo 自动复活
        //    注意：新的 :memory: 连接是全新的空库——连接即库，这正是服务随 fiber 生灭的体现
        System.out.println("\n-- registry.use(\"sqlite\") again --");
        root.registry.use("sqlite", SqlitePlugin.INSTANCE,
                map("url", "jdbc:sqlite::memory:"), map());
        System.out.println("repoFiber = " + repoFiber);
        System.out.println("root.has(\"sqlite\") = " + root.has("sqlite"));
    }
}
