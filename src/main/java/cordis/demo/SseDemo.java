package cordis.demo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cordis.core.Context;
import cordis.core.Fiber;
import cordis.http.HttpPlugin;
import cordis.http.HttpService;
import cordis.http.SseCall;
import cordis.http.SseEvent;
import cordis.http.SseListener;
import cordis.util.Maps;
import okhttp3.Request;

/**
 * SSE 流式插件演示：推送接收、主循环零阻塞、主动取消、错误路径。
 *
 * <p>本地服务用 JDK 自带的 {@code com.sun.net.httpserver}，零额外依赖：
 *
 * <ul>
 *   <li>{@code /events?n=8&ms=60}：按间隔逐条推送，走 chunked 编码；</li>
 *   <li>{@code /stall}：推 2 条后挂住，用来验证取消；</li>
 *   <li>{@code /error}：返回 404。</li>
 * </ul>
 *
 * <p>第 1、2 节各起一个探针线程，每 10ms 往主循环投一个任务并测量排队时间。
 * 这是"主循环有没有被流式读阻塞"的直接证据：整段流式传输期间，主循环的
 * 最大排队延迟应该稳定在微秒级，与流的持续时间无关。
 */
public final class SseDemo {

    private static final String BASE = "http://127.0.0.1:";

    /** 主循环排队延迟样本（微秒）；只看 max 会被偶发 GC 误导，故统计分位 */
    private static final List<Long> LOOP_LATENCY_US =
            Collections.synchronizedList(new ArrayList<Long>());

    private static final AtomicBoolean PROBING = new AtomicBoolean(true);

    private SseDemo() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = startServer();
        String base = BASE + server.getAddress().getPort();

        Context root = Context.root();
        Fiber httpFiber = root.registry.use(HttpPlugin.INSTANCE,
                Maps.of("connectTimeoutMs", 2000, "streamReadTimeoutMs", 0), null).await().join();

        startProbe(root);

        try {
            sectionControl(root);
            sectionReceive(root, base);
            sectionCancel(root, base);
            sectionError(root, base);
        } finally {
            PROBING.set(false);
            httpFiber.dispose();
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
            server.stop(0);
        }
    }

    /**
     * 0. 对照组：先证明探针是灵敏的。
     *
     * 同一个指标，主循环上故意阻塞 50ms，排队延迟立刻跳到 5 万微秒量级；
     * 后面几节的低数值才有意义——否则无法排除"探针本身测不出停顿"。
     */
    private static void sectionControl(Context root) throws Exception {
        resetLatency();
        root.scheduler.execute(new Runnable() {

            @Override
            public void run() {
                sleep(50);
            }

        });
        Thread.sleep(200);
        System.out.println("== 0. 对照组：探针灵敏度 ==");
        System.out.println("   在主循环上阻塞 50ms 一次 -> " + latencyReport());
        System.out.println("   说明: 该指标会如实暴露主循环停顿，后面的低值不是测不出来");
        System.out.println();
    }

    /** 1. 逐条推送：事件在 IO 线程到达，切回主循环累加 */
    private static void sectionReceive(Context root, String base) throws Exception {
        final HttpService http = root.get("http");
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        resetLatency();

        long start = System.nanoTime();
        SseCall call = http.stream(new Request.Builder().url(base + "/events?n=8&ms=60").build(),
                new SseListener() {

                    @Override
                    public void onEvent(final SseEvent event) {
                        // 模拟插件里的状态更新：一律切回主循环，杜绝跨线程写状态
                        root.scheduler.execute(new Runnable() {

                            @Override
                            public void run() {
                                received.add(event.id + ":" + event.data);
                            }

                        });
                    }

                });
        call.completion().join();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        System.out.println("== 1. 流式接收（8 条，服务器每 60ms 推一条）==");
        System.out.println("   收到 " + received.size() + " 条: " + received.get(0)
                + " ... " + received.get(received.size() - 1));
        System.out.println("   耗时 " + elapsed + "ms（理论下限 8 x 60 = 480ms）");
        System.out.println("   流传输期间主循环排队延迟: " + latencyReport());
        System.out.println("   说明: completion 结算与事件回投走同一条主循环队列，"
                + "FIFO 保证 join 返回时事件已全部落袋");
        System.out.println();
    }

    /** 2. 主动取消：dispose 之后既不再有事件，也有明确的取消原因 */
    private static void sectionCancel(Context root, String base) throws Exception {
        final HttpService http = root.get("http");
        final AtomicInteger count = new AtomicInteger();
        resetLatency();

        SseCall call = http.stream(new Request.Builder().url(base + "/stall").build(),
                new SseListener() {

                    @Override
                    public void onEvent(SseEvent event) {
                        count.incrementAndGet();
                    }

                });

        long deadline = System.currentTimeMillis() + 2000;
        while (count.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        int beforeCancel = count.get();
        call.dispose();

        String reason = "(none)";
        try {
            call.completion().join();
        } catch (CancellationException e) {
            reason = e.toString();
        } catch (RuntimeException e) {
            reason = e.toString();
        }

        Thread.sleep(400);
        System.out.println("== 2. 主动取消 ==");
        System.out.println("   取消前收到 " + beforeCancel + " 条");
        System.out.println("   completion: " + reason);
        System.out.println("   取消后 400ms 内新增事件 = " + (count.get() - beforeCancel)
                + "（应为 0）");
        System.out.println("   主循环排队延迟: " + latencyReport());
        System.out.println();
    }

    /** 3. 非 2xx：错误响应体的开头会被带进异常消息 */
    private static void sectionError(Context root, String base) {
        final HttpService http = root.get("http");
        SseCall call = http.stream(new Request.Builder().url(base + "/error").build(),
                new SseListener() {

                    @Override
                    public void onEvent(SseEvent event) {
                        System.out.println("   unexpected event: " + event);
                    }

                });
        System.out.println("== 3. 非 2xx ==");
        try {
            call.completion().join();
            System.out.println("   unexpected success");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("   rejected: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
        }
        System.out.println();
    }

    // ---------- 本地 SSE 服务 ----------

    private static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "demo-sse-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);

        server.createContext("/events", ex -> {
            int n = param(ex, "n", 8);
            long ms = param(ex, "ms", 60);
            startStream(ex);
            OutputStream out = ex.getResponseBody();
            try {
                for (int i = 1; i <= n; i++) {
                    writeEvent(out, String.valueOf(i), "delta", "{\"i\":" + i + "}");
                    sleep(ms);
                }
            } catch (IOException ignored) {
                // 对端已取消，正常情况
            } finally {
                out.close();
            }
        });

        server.createContext("/stall", ex -> {
            startStream(ex);
            OutputStream out = ex.getResponseBody();
            try {
                writeEvent(out, "1", "delta", "{\"i\":1}");
                writeEvent(out, "2", "delta", "{\"i\":2}");
                sleep(5000);
            } catch (IOException ignored) {
                // 对端已取消，正常情况
            } finally {
                out.close();
            }
        });

        server.createContext("/error", ex -> {
            byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, body.length);
            OutputStream out = ex.getResponseBody();
            out.write(body);
            out.close();
        });

        server.start();
        return server;
    }

    /** length = 0 让 HttpServer 使用 chunked，逐条写出才会立刻到达对端 */
    private static void startStream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
    }

    private static void writeEvent(OutputStream out, String id, String event, String data)
            throws IOException {
        String block = "id: " + id + "\nevent: " + event + "\ndata: " + data + "\n\n";
        out.write(block.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static int param(HttpExchange ex, String key, int fallback) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return Integer.parseInt(pair.substring(eq + 1));
            }
        }
        return fallback;
    }

    // ---------- 主循环延迟探针 ----------

    private static void startProbe(final Context root) {
        Thread probe = new Thread(() -> {
            while (PROBING.get()) {
                final long start = System.nanoTime();
                root.scheduler.execute(new Runnable() {

                    @Override
                    public void run() {
                        long us = (System.nanoTime() - start) / 1000;
                        LOOP_LATENCY_US.add(us);
                    }

                });
                sleep(10);
            }
        }, "demo-loop-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private static void resetLatency() {
        LOOP_LATENCY_US.clear();
    }

    /** p50 / p99 / max：p50 反映常态，max 反映最坏停顿（通常是 GC） */
    private static String latencyReport() {
        List<Long> copy;
        synchronized (LOOP_LATENCY_US) {
            copy = new ArrayList<Long>(LOOP_LATENCY_US);
        }
        if (copy.isEmpty()) {
            return "no samples";
        }
        Collections.sort(copy);
        int n = copy.size();
        long p50 = copy.get(n / 2);
        long p99 = copy.get(Math.min(n - 1, n * 99 / 100));
        long max = copy.get(n - 1);
        return "p50=" + p50 + "µs p99=" + p99 + "µs max=" + max + "µs (n=" + n + ")";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
