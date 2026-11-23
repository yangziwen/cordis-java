package cordis.demo;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.core.Service;
import cordis.util.Maps;

/**
 * 演示 cordis 的典型用法，展示改写后的 Java 版本如何还原原框架的行为：
 *
 * 1. 创建根上下文；
 * 2. 加载数据库插件：apply 中创建服务、监听事件，并返回清理函数；
 * 3. 加载服务器插件：声明依赖 database，服务就绪后才激活；
 * 4. 广播事件（emit）、串行截获（serial）、中间件链（waterfall）；
 * 5. 销毁数据库插件 —— 服务器插件因依赖消失而自动卸载；
 * 6. 重新加载数据库插件 —— 服务器插件又自动恢复（这是 cordis 最核心的
 *    “依赖驱动的生命周期”特性）。
 *
 * 运行方式：
 *   mvn compile
 *   java -cp target/classes cordis.demo.Demo
 */
public class Demo {

    /** 一个简单的服务，对应 extends Service 的具名单例 */
    static class DatabaseService extends Service {
        final String url;

        DatabaseService(Context ctx, String url) {
            super(ctx, "database");
            this.url = url;
        }

        String query(String sql) {
            return "rows-of(" + sql + ")@" + url;
        }

        @Override
        public String toString() {
            return "DatabaseService(" + url + ")";
        }
    }

    /** 数据库插件，对应 Component */
    static final Component DATABASE = (ctx, config) -> {
        String url = (String) config.getOrDefault("url", "sqlite://demo.db");
        DatabaseService db = new DatabaseService(ctx, url);
        System.out.println("[database] connection opened: " + url);

        // ctx.on() 本身就是一个 effect：fiber 卸载时监听器自动解绑
        ctx.on("request", args -> {
            System.out.println("[database] serving -> " + db.query((String) args[0]));
            return null;
        });

        // 插件主体返回清理函数，对应 TS 中插件返回 Disposable
        return () -> System.out.println("[database] connection closed: " + url);
    };

    /** 服务器插件：声明依赖 database */
    static final Component SERVER = (ctx, config) -> {
        DatabaseService db = ctx.get("database");
        System.out.println("[server] started, resolving database -> " + db);

        ctx.on("request", args -> {
            System.out.println("[server] handle \"" + args[0] + "\" via " + db.name);
            return null;
        });
        return null;
    };

    public static void main(String[] argv) {
        // 1. 根上下文
        Context root = Context.root();

        // 2. 加载数据库插件
        Fiber dbFiber = root.registry.use("database", DATABASE,
                Maps.of("url", "mysql://prod:3306/app"), Maps.of());
        System.out.println("dbFiber = " + dbFiber);

        // 3. 加载服务器插件：inject 声明依赖 database
        Fiber serverFiber = root.registry.use("server", SERVER,
                Maps.of(), Maps.of("database", null));
        System.out.println("serverFiber = " + serverFiber + "\n");

        // 4a. emit：广播事件，两个插件的监听器都被调用
        System.out.println("-- emit(\"request\") --");
        root.events.emit("request", "SELECT * FROM users");

        // 4b. serial：第一个返回非 null 的监听器截获事件
        System.out.println("\n-- serial(\"login\") --");
        dbFiber.ctx.on("login", args -> {
            System.out.println("[audit] login attempt: " + args[0]);
            return null; // 不截获
        });
        dbFiber.ctx.on("login", args -> {
            System.out.println("[auth] rejecting " + args[0]);
            return "rejected"; // 截获，后面的监听器不再执行
        });
        dbFiber.ctx.on("login", args -> {
            System.out.println("[should-not-print]");
            return null;
        });
        System.out.println("serial result = " + root.events.serial("login", "mallory"));

        // 4c. waterfall：监听器像中间件一样依次加工数据
        System.out.println("\n-- waterfall(\"pipeline\") --");
        root.on("pipeline", args -> args[1] + " -> step1");
        root.on("pipeline", args -> args[1] + " -> step2");
        System.out.println("waterfall result = "
                + root.events.waterfall("pipeline", "raw-input", "prefix"));

        // 4d. isolate：隔离分支中的上下文看不见外部的服务
        System.out.println("\n-- isolate --");
        Context isolated = root.isolate("database");
        System.out.println("root sees database      = " + root.has("database"));
        System.out.println("isolated sees database  = " + isolated.has("database"));

        // 4e. intercept：在本分支覆盖服务的配置
        System.out.println("intercept on root.branch = "
                + root.intercept("database", "custom-config").interceptConfig("database"));

        // 5. 销毁数据库插件：依赖它的服务器插件被自动卸载
        System.out.println("\n-- dbFiber.dispose() --");
        dbFiber.dispose();
        System.out.println("dbFiber     = " + dbFiber);
        System.out.println("serverFiber = " + serverFiber);
        System.out.println("root.has(\"database\") = " + root.has("database"));

        // 6. 重新加载数据库插件：服务器插件自动恢复
        System.out.println("\n-- registry.use(\"database\") again --");
        root.registry.use("database", DATABASE, Maps.of("url", "sqlite://backup.db"), Maps.of());
        System.out.println("serverFiber = " + serverFiber);

        System.out.println("\n-- emit(\"request\") after reload --");
        root.events.emit("request", "SELECT 1");

        // 7. parallel：收集所有监听器的异常后合并抛出
        System.out.println("\n-- parallel(\"boom\") --");
        root.on("boom", args -> { throw new RuntimeException("listener-1 failed"); });
        root.on("boom", args -> { throw new RuntimeException("listener-2 failed"); });
        try {
            root.events.parallel("boom");
        } catch (IllegalStateException e) {
            System.out.println("caught: " + e.getMessage());
            for (Throwable t : e.getSuppressed()) {
                System.out.println("  suppressed: " + t.getMessage());
            }
        }
    }
}
