package cordis.demo;

import java.util.Arrays;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.core.Promise;
import cordis.core.Service;
import cordis.util.Maps;

/**
 * 多租户隔离演示（异步版）：在裸隔离上下文上继续派生并 use() 插件。
 *
 * 结构（隔离层只做标签遮蔽，fiber 挂在它的子节点上）：
 *
 *   root
 *    ├─ isolate tenant1（隔离 "database"，不带 fiber）
 *    │    └─ fiber ctx：db1（提供租户1的 database）+ repo1（依赖 database）
 *    └─ isolate tenant2（隔离 "database"，不带 fiber）
 *         └─ fiber ctx：db2（提供租户2的 database）+ repo2（依赖 database）
 *
 * 要点：
 * 1. 在隔离上下文上 use() 必须显式传 caller（对应 TS Proxy 绑定的 thisArg），
 *    否则插件会被挂到 root 下，隔离标签不生效；
 * 2. 两个租户的同名服务在全局仓库中以不同的隔离标签存放，互不可见；
 * 3. root 看不见任何租户的服务；租户分支内按名字解析到自己的服务；
 * 4. 依赖传播按租户精确隔离：卸载 db1 只影响 repo1，repo2 保持 ACTIVE；
 * 5. 编排层用 Promise.all + ready() 等待两租户全部就绪，用 awaitIdle()
 *    等待主循环消化完 dispose/reload 动作后再断言状态。
 *
 * 运行方式：
 *   mvn compile
 *   java -cp target/classes cordis.demo.TenantDemo
 */
public class TenantDemo {

    static class DbService extends Service {
        final String url;

        DbService(Context ctx, String url) {
            super(ctx, "database");
            this.url = url;
        }

        @Override
        public String toString() {
            return "DbService(" + url + ")";
        }
    }

    /** 数据库插件：每次激活在自己的分支上提供一个 DatabaseService */
    static final Component DATABASE = Component.named("database", (ctx, config) -> {
        DbService db = new DbService(ctx, (String) config.get("url"));
        System.out.println("[database] opened " + db);
        return null;
    });

    /** 数据访问插件：按名字解析本租户分支内的 database 服务 */
    static final Component REPO = Component.named("repo", (ctx, config) -> {
        System.out.println("[repo] resolves database -> " + ctx.get("database"));
        return null;
    });

    public static void main(String[] argv) {
        Context root = Context.root();

        // 两个租户分支：各自隔离 "database"，都是裸上下文（不带 fiber）
        Context tenant1 = root.isolate("database");
        Context tenant2 = root.isolate("database");

        // 直接在裸上下文上 use()（显式传 caller）：fiber 的 ctx 是隔离上下文的子节点
        Fiber db1 = tenant1.registry.use(tenant1, DATABASE, Maps.of("url", "tenant1://ds1"), Maps.of());
        Fiber repo1 = tenant1.registry.use(tenant1, REPO, Maps.of(), Maps.of("database", null));
        Fiber db2 = tenant2.registry.use(tenant2, DATABASE, Maps.of("url", "tenant2://ds2"), Maps.of());
        Fiber repo2 = tenant2.registry.use(tenant2, REPO, Maps.of(), Maps.of("database", null));

        // 编排层等待两个租户的全部 fiber 就绪（对应 await fiber）
        Promise.all(root.scheduler, Arrays.asList(
                db1.await(), repo1.await(), db2.await(), repo2.await())).join();

        System.out.println();
        System.out.println("db1=" + db1 + "  repo1=" + repo1);
        System.out.println("db2=" + db2 + "  repo2=" + repo2);
        System.out.println("root.has(\"database\")    = " + root.has("database"));
        System.out.println("tenant1.has(\"database\") = " + tenant1.has("database"));
        System.out.println("tenant2.has(\"database\") = " + tenant2.has("database"));

        // 卸载租户1的数据库：租户1的 repo 自动卸载，租户2不受影响
        System.out.println("\n-- db1.dispose() --");
        db1.dispose();
        root.scheduler.awaitIdle();
        System.out.println("repo1 = " + repo1 + "   (PENDING，等租户1服务恢复)");
        System.out.println("repo2 = " + repo2 + "   (不受影响)");

        // 租户1恢复：repo1 自动复活
        System.out.println("\n-- tenant1 重新加载数据库 --");
        tenant1.registry.use(tenant1, DATABASE, Maps.of("url", "tenant1://ds1-backup"), Maps.of());
        root.scheduler.awaitIdle();
        System.out.println("repo1 = " + repo1);

        // 清理剩余插件
        repo1.dispose();
        db2.dispose();
        repo2.dispose();
        root.scheduler.awaitIdle();

        root.scheduler.shutdown();
    }
}
