package cordis.demo;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Fiber;
import cordis.core.Promise;
import cordis.http.HttpPlugin;
import cordis.http.HttpResponse;
import cordis.http.HttpService;
import cordis.util.Maps;

/**
 * HTTP 插件演示：全部请求打向本地启动的 JDK HttpServer，不依赖外网。
 *
 * 覆盖四件事：
 * <ol>
 *   <li>装配：http 与依赖它的 fetcher 依次进入 ACTIVE；</li>
 *   <li>并发：10 个 200ms 的请求并发发出，总耗时接近单次；</li>
 *   <li>热插拔：dispose http 后 fetcher 自动退回 PENDING，重新 use 后自动
 *       重载并重新发起请求；</li>
 *   <li>失败：连接被拒绝时 Promise reject，不污染主循环。</li>
 * </ol>
 *
 * 运行：
 * <pre>
 *   mvn compile
 *   java -cp target/classes:cordis-java.jar:okhttp.jar:okio.jar cordis.demo.HttpDemo
 * </pre>
 */
public class HttpDemo {

    static final AtomicInteger HELLO_COUNT = new AtomicInteger();

    /** 依赖 http 服务的插件：装配时立即发一次请求 */
    static final class Fetcher implements Component {

        @Override
        public String getName() {
            return "fetcher";
        }

        @Override
        public Disposable apply(Context ctx, Map<String, Object> config) {
            final String base = String.valueOf(config.get("base"));
            final HttpService http = ctx.get("http");
            final long t0 = System.nanoTime();
            // 不阻塞：请求在 OkHttp 的 Dispatcher 线程上跑，结果回到主循环
            http.get(base + "/hello").then(r -> {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("[fetcher] GET /hello -> " + r.status + " " + r.body
                        + "   (" + ms + "ms)");
                return null;
            }).catchError(e -> {
                System.out.println("[fetcher] 请求失败: " + e);
                return null;
            });
            return null;
        }

    }

    public static void main(String[] args) throws Exception {
        final HttpServer server = startServer();
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        System.out.println("== 1. 装配 ==");
        System.out.println("[demo] 本地服务 " + base + "  (/hello, /slow?ms=200)");

        final Context root = Context.root();
        final Fiber httpFiber = root.registry.use(HttpPlugin.INSTANCE,
                Maps.of("connectTimeoutMs", 2000, "maxRequests", 64), null);
        final Fiber fetcherFiber = root.registry.use(
                root.extend(), new Fetcher(), Maps.of("base", base), Maps.of("http", true));

        httpFiber.await().join();
        fetcherFiber.await().join();
        Thread.sleep(200);                                  // 等首个请求回调打印
        System.out.println("   httpFiber    = " + httpFiber);
        System.out.println("   fetcherFiber = " + fetcherFiber);

        // ---------- 2. 并发 ----------
        System.out.println();
        System.out.println("== 2. 10 个并发请求（每个 /slow 延迟 200ms）==");
        final HttpService http = root.get("http");
        List<Promise<HttpResponse>> calls = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            calls.add(http.get(base + "/slow?ms=200"));
        }
        List<HttpResponse> responses = Promise.all(root.scheduler, calls).join();
        long cost = (System.nanoTime() - t0) / 1_000_000;
        int ok = 0;
        for (HttpResponse r : responses) {
            if (r.isSuccessful()) {
                ok++;
            }
        }
        System.out.println("   总耗时 " + cost + "ms（串行需 ~2000ms）  成功 " + ok + "/" + responses.size());

        // ---------- 3. 依赖驱动的热插拔 ----------
        System.out.println();
        System.out.println("== 3. 热插拔：http 消失 -> fetcher 自动卸载；http 回来 -> 自动重载 ==");
        int before = HELLO_COUNT.get();
        httpFiber.dispose();
        root.scheduler.awaitIdle();
        System.out.println("   移除 http 后: fetcher = " + fetcherFiber + "  (等依赖)");
        Fiber httpAgain = root.registry.use(HttpPlugin.INSTANCE,
                Maps.of("connectTimeoutMs", 2000), null);
        httpAgain.await().join();
        Thread.sleep(200);
        System.out.println("   重新装配后:   fetcher = " + fetcherFiber
                + "   /hello 被重新请求 " + (HELLO_COUNT.get() - before) + " 次");

        // ---------- 4. 失败路径 ----------
        System.out.println();
        System.out.println("== 4. 失败路径：连接被拒绝 ==");
        try {
            HttpService latest = root.get("http");
            latest.get("http://127.0.0.1:1/nothing").join();
            System.out.println("   没有报错（不应发生）");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("   rejected: " + cause.getClass().getSimpleName()
                    + " -> " + cause.getMessage());
        }

        System.out.println();
        System.out.println("主循环线程 = " + root.scheduler.mainLoopThread().getName()
                + "；本行之后主循环仍在正常调度");
        root.scheduler.execute(() -> System.out.println("[demo] 主循环任务照常执行"));
        root.scheduler.awaitIdle();

        server.stop(0);
        root.scheduler.shutdown();
    }

    /** 起一个多线程的本地服务：单线程只能串行处理，测不出并发效果 */
    private static HttpServer startServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> {
            byte[] body = ("{\"msg\":\"hello\",\"n\":" + HELLO_COUNT.incrementAndGet() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            write(exchange, 200, body);
        });
        server.createContext("/slow", exchange -> {
            String q = exchange.getRequestURI().getQuery();
            long ms = 200;
            if (q != null && q.startsWith("ms=")) {
                ms = Long.parseLong(q.substring(3));
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            write(exchange, 200, "{\"slept\":true}".getBytes(StandardCharsets.UTF_8));
        });
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.start();
        return server;
    }

    private static void write(HttpExchange exchange, int status, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

}
