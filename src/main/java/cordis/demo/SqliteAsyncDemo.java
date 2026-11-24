package cordis.demo;

import java.util.List;
import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.sqlite.SqlitePlugin;
import cordis.sqlite.SqliteService;
import cordis.util.Maps;

/**
 * SQLite 服务的完整演示（异步版），重点展示两件事：
 *
 * A. 依赖驱动的生命周期：
 *    1. 先加载数据访问组件（声明依赖 sqlite，但服务尚不存在 → PENDING）；
 *    2. 再加载 SQLite 插件（内存库）→ 服务注册 → 数据访问组件自动激活；
 *    3. 建表、参数化插入、查询；
 *    4. 事件触发查询 —— 监听器把阻塞 JDBC offload 到扩展池，返回 Promise，
 *       主循环零阻塞；
 *    5. 卸载 SQLite → 依赖者自动卸载、连接关闭；
 *    6. 重新加载 SQLite → 依赖者自动复活。
 *
 * B. 双层线程模型：
 *    - JDBC 是阻塞调用，永远只出现在两条路径上：
 *      ① main 编排线程（本 demo 的建表/插入，main 不属于 cordis 线程模型）；
 *      ② scheduler.offload(...) → cordis-worker 扩展池（事件监听器内）。
 *    - cordis-main-loop 主循环上只做调度决策与回调派发。
 *
 * 运行方式：
 *   mvn compile
 *   java -cp "target/classes:$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.21.0.1/sqlite-jdbc-3.21.0.1.jar" \
 *        cordis.demo.SqliteAsyncDemo
 */
public class SqliteAsyncDemo {

    /** 数据访问组件：声明依赖 sqlite（声明在 use() 的 inject 参数里，见 main） */
    static final Component USER_REPO = Component.named("user-repo", (ctx, config) -> {
        SqliteService db = ctx.get("sqlite");
        System.out.println("[user-repo] started with " + db);

        // 监听器是 repo fiber 的 effect：repo 卸载时自动解绑，重载时重新注册。
        // JDBC 查询是阻塞的 → offload 到扩展池，返回 Promise；
        // 事件系统会等待 Promise 结算（异步感知派发）。
        ctx.on("user-created", args -> ctx.scheduler.offload(() -> {
            Map<String, Object> row = db.queryOne(
                    "SELECT id, name, age FROM users WHERE id = ?", args[0]);
            System.out.println("[user-repo] user-created -> " + row
                    + "  (on " + Thread.currentThread().getName() + ")");
            return null;
        }));
        return null;
    });

    public static void main(String[] argv) {
        Context root = Context.root();

        // 1. 先加载数据访问组件：sqlite 服务尚不存在 → 停在 PENDING
        Fiber repoFiber = root.registry.use(USER_REPO,
                Maps.of(), Maps.of("sqlite", null));
        System.out.println("repoFiber = " + repoFiber + "   (等待 sqlite 服务...)");

        // 2. 加载 SQLite 插件（内存库）→ 服务注册 → repo 自动激活
        Fiber dbFiber = root.registry.use(SqlitePlugin.INSTANCE,
                Maps.of("url", "jdbc:sqlite::memory:"), Maps.of());
        System.out.println("dbFiber   = " + dbFiber);

        // 编排层等待两根 fiber 就绪（对应 await fiber）
        dbFiber.ready().join();
        repoFiber.ready().join();
        System.out.println("dbFiber   = " + dbFiber);
        System.out.println("repoFiber = " + repoFiber + "   (依赖就绪自动激活)\n");

        SqliteService db = root.get("sqlite");

        // 3. 建表 + 参数化插入 + 查询
        //    （在 main 编排线程直接调用阻塞 JDBC 是允许的：
        //     main 不属于 cordis 的调度线程模型）
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

        // 4. 事件触发查询：监听器在扩展池里跑 JDBC（见 USER_REPO 的定义）。
        //    serial 会等待异步监听器返回的 Promise 结算（异步感知派发）；
        //    emit 是 fire-and-forget、无法等待，这里用 serial 保证确定性。
        System.out.println("\n-- serial(\"user-created\") --");
        root.events.serial("user-created", 2).join();

        // 5. 卸载 SQLite：依赖者自动卸载，连接关闭
        System.out.println("\n-- dbFiber.dispose() --");
        dbFiber.dispose();
        root.scheduler.awaitIdle();
        System.out.println("dbFiber   = " + dbFiber);
        System.out.println("repoFiber = " + repoFiber);
        System.out.println("root.has(\"sqlite\") = " + root.has("sqlite"));

        // 6. 重新加载：repo 自动复活
        //    注意：新的 :memory: 连接是全新的空库——连接即库，这正是服务随 fiber 生灭的体现
        System.out.println("\n-- registry.use(\"sqlite\") again --");
        root.registry.use(SqlitePlugin.INSTANCE,
                Maps.of("url", "jdbc:sqlite::memory:"), Maps.of());
        root.scheduler.awaitIdle();
        System.out.println("repoFiber = " + repoFiber);
        System.out.println("root.has(\"sqlite\") = " + root.has("sqlite"));

        root.scheduler.shutdown();
    }
}
