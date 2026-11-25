package cordis.demo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.core.Promise;
import cordis.core.Service;
import cordis.util.Maps;

/**
 * 演示 cordis-java 的全异步调度模型（对应“JS 主线程 + libuv 线程池”）：
 *
 * 1. 创建根上下文（自带 Scheduler：cordis-main-loop 主循环 + cordis-worker 扩展池）；
 * 2. 加载一个慢初始化插件：applyAsync 中把阻塞工作 offload 到扩展池，
 *    fiber 停在 LOADING——主循环不被阻塞，其他插件照常并行挂载；
 * 3. 加载数据库/服务器插件，Promise.all 等待全部就绪；
 * 4. Promise 组合子（then / thenAsync / all / catchError）：
 *    阻塞工作在扩展池执行，回调保证回到主循环；
 * 5. 事件派发：emit（广播）、serial（截获）、waterfall（中间件链）；
 *    监听器返回 Promise 时自动进入异步链路；
 * 6. 销毁数据库插件 —— 服务器插件因依赖消失而自动卸载；
 *    重新加载 —— 服务器插件又自动恢复；
 * 7. parallel：收集所有监听器的异常后聚合 reject。
 *
 * main 编排线程允许的阻塞点只有 Promise.join() / awaitIdle()，
 * 主循环上永远不做任何阻塞工作。
 *
 * 运行方式：
 *   mvn compile
 *   java -cp target/classes cordis.demo.LifecycleDemo
 */
public class LifecycleDemo {

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

    /**
     * 数据库插件（轻量初始化，直接走同步 apply）。
     * apply 运行在主循环上，因此必须轻量——真实场景的阻塞工作
     * 参考下面 SLOW_INIT 的 offload 写法。
     */
    static final Component DATABASE = Component.named("database", (ctx, config) -> {
        String url = (String) config.getOrDefault("url", "sqlite://demo.db");
        DatabaseService db = new DatabaseService(ctx, url);
        System.out.println("[database] connection opened: " + url);

        // ctx.on() 本身就是一个 effect：fiber 卸载时监听器自动解绑
        ctx.on("request", args -> {
            System.out.println("[database] serving -> " + db.query((String) args[0]));
            return null;
        });

        // 插件主体返回清理函数，对应 TS 中插件返回 Disposable
        return (Disposable) () -> System.out.println("[database] connection closed: " + url);
    });

    /** 服务器插件：声明依赖 database，服务就绪后才激活 */
    static final Component SERVER = Component.named("server", (ctx, config) -> {
        DatabaseService db = ctx.get("database");
        System.out.println("[server] started, resolving database -> " + db);

        ctx.on("request", args -> {
            System.out.println("[server] handle \"" + args[0] + "\" via " + db.name);
            return null;
        });
        return null;
    });

    /**
     * 慢初始化插件：覆写 applyAsync，把阻塞初始化（模拟建连/握手）offload
     * 到扩展池。返回的 Promise 结算前 fiber 停在 LOADING——主循环照常
     * 调度其他插件，等价于 TS 中插件返回 Promise / Netty 中不阻塞 EventLoop。
     */
    static final Component SLOW_INIT = new Component() {

        @Override
        public String getName() {
            return "slow-init";
        }

        @Override
        public Disposable apply(Context ctx, Map<String, Object> config) {
            throw new UnsupportedOperationException("本插件只走 applyAsync");
        }

        @Override
        public Promise<Disposable> applyAsync(Context ctx, Map<String, Object> config) {
            long delay = ((Number) config.getOrDefault("delay", 300)).longValue();
            return ctx.scheduler.offload(() -> {
                Thread.sleep(delay); // 阻塞工作：只在扩展池（cordis-worker）上执行
                System.out.println("[slow-init] ready after " + delay + "ms on "
                        + Thread.currentThread().getName());
                return (Disposable) () ->
                        System.out.println("[slow-init] closed on "
                                + Thread.currentThread().getName());
            });
        }

    };

    public static void main(String[] argv) throws Exception {
        // 1. 根上下文（自带 Scheduler：主循环 + 扩展阻塞池）
        Context root = Context.root();

        // 2. 慢初始化插件：fiber 将停在 LOADING，不阻塞主循环
        Fiber slowFiber = root.registry.use(SLOW_INIT, Maps.of("delay", 300), Maps.of());

        // 3. 数据库 + 服务器插件：use() 立即返回，挂载投递到主循环
        Fiber dbFiber = root.registry.use(DATABASE,
                Maps.of("url", "mysql://prod:3306/app"), Maps.of());
        Fiber serverFiber = root.registry.use(SERVER,
                Maps.of(), Maps.of("database", null));
        System.out.println("slowFiber   = " + slowFiber + "   (初始化中...)");
        System.out.println("dbFiber     = " + dbFiber);
        System.out.println("serverFiber = " + serverFiber + "   (等待依赖...)");

        // 编排层等待就绪（对应 await fiber）：Promise.all 聚合三根 fiber 的 await
        Promise.all(root.scheduler, Arrays.asList(
                slowFiber.await(), dbFiber.await(), serverFiber.await())).join();
        System.out.println("slowFiber   = " + slowFiber);
        System.out.println("dbFiber     = " + dbFiber);
        System.out.println("serverFiber = " + serverFiber);
        System.out.println("(慢插件 LOADING 期间，db/server 已在主循环上并行挂载完成)\n");

        // 4. Promise 组合子：阻塞工作在扩展池，回调回到主循环
        System.out.println("-- Promise.all + then --");
        Promise<String> q1 = root.scheduler.offload(() -> {
            Thread.sleep(100);
            return "result-1";
        });
        Promise<String> q2 = root.scheduler.offload(() -> {
            Thread.sleep(50);
            return "result-2";
        });
        List<String> both = Promise.all(root.scheduler, Arrays.asList(q1, q2)).join();
        System.out.println("all -> " + both);

        System.out.println("-- thenAsync 链 --");
        String chained = root.scheduler.offload(() -> "step-1")
                .thenAsync(v -> root.scheduler.offload(() -> v + " -> step-2"))
                .then(v -> v + " -> step-3")
                .join();
        System.out.println("chain -> " + chained);

        System.out.println("-- catchError --");
        String recovered = root.scheduler.<String>offload(() -> {
            throw new IllegalStateException("boom");
        }).catchError(e -> "recovered: " + e.getMessage()).join();
        System.out.println(recovered);

        // 5a. emit：广播事件，两个插件的监听器都被调用（fire-and-forget）
        System.out.println("\n-- emit(\"request\") --");
        root.events.emit("request", "SELECT * FROM users");
        root.scheduler.awaitIdle();

        // 5b. serial：第一个返回非 null 的监听器截获事件（返回 Promise）
        System.out.println("\n-- serial(\"login\") --");
        dbFiber.ctx.on("login", args -> {
            System.out.println("[audit] login attempt: " + args[0]);
            return null; // 不截获
        });
        dbFiber.ctx.on("login", args ->
                // 异步监听器：返回 Promise，serial 会等它结算
                root.scheduler.offload(() -> {
                    System.out.println("[auth] rejecting " + args[0] + " on "
                            + Thread.currentThread().getName());
                    return "rejected"; // 结算值非 null -> 截获
                }));
        dbFiber.ctx.on("login", args -> {
            System.out.println("[should-not-print]");
            return null;
        });
        System.out.println("serial result = " + root.events.serial("login", "mallory").join());

        // 5c. waterfall：监听器像中间件一样依次加工数据（返回 Promise）
        System.out.println("\n-- waterfall(\"pipeline\") --");
        root.on("pipeline", args -> args[1] + " -> step1");
        root.on("pipeline", args -> args[1] + " -> step2");
        System.out.println("waterfall result = "
                + root.events.waterfall("pipeline", "raw-input", "prefix").join());

        // 5d. isolate：隔离分支中的上下文看不见外部的服务
        System.out.println("\n-- isolate --");
        Context isolated = root.isolate("database");
        System.out.println("root sees database      = " + root.has("database"));
        System.out.println("isolated sees database  = " + isolated.has("database"));

        // 6. 销毁数据库插件：依赖它的服务器插件被自动卸载
        System.out.println("\n-- dbFiber.dispose() --");
        dbFiber.dispose();
        root.scheduler.awaitIdle();
        System.out.println("dbFiber     = " + dbFiber);
        System.out.println("serverFiber = " + serverFiber);
        System.out.println("root.has(\"database\") = " + root.has("database"));

        // 重新加载数据库插件：服务器插件自动恢复
        System.out.println("\n-- registry.use(\"database\") again --");
        root.registry.use(DATABASE, Maps.of("url", "sqlite://backup.db"), Maps.of());
        root.scheduler.awaitIdle();
        System.out.println("serverFiber = " + serverFiber);

        System.out.println("\n-- emit(\"request\") after reload --");
        root.events.emit("request", "SELECT 1");
        root.scheduler.awaitIdle();

        // 7. parallel：收集所有监听器的异常后聚合 reject（Promise 语义）
        System.out.println("\n-- parallel(\"boom\") --");
        root.on("boom", args -> { throw new RuntimeException("listener-1 failed"); });
        root.on("boom", args -> { throw new RuntimeException("listener-2 failed"); });
        try {
            root.events.parallel("boom").join();
        } catch (IllegalStateException e) {
            System.out.println("caught: " + e.getMessage());
            for (Throwable t : e.getSuppressed()) {
                System.out.println("  suppressed: " + t.getMessage());
            }
        }

        root.scheduler.shutdown();
    }

}
