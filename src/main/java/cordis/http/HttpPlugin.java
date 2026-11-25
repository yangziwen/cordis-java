package cordis.http;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import okhttp3.OkHttpClient;

/**
 * HTTP 插件：注册一个 {@link HttpService}，供其他插件通过 {@code inject} 声明依赖。
 *
 * <h3>为什么用同步 {@code apply} 而不是 {@code applyAsync}</h3>
 *
 * 与 SqlitePlugin 形成对照：JDBC 的 {@code DriverManager.getConnection} 会
 * 真的去建连（阻塞），必须 offload；而 {@code new OkHttpClient()} 只初始化
 * 连接池、Dispatcher 等内存结构，<b>不发起任何网络 IO</b>——连接是在第一次
 * 请求时才懒建立的。因此这里留在主循环上、微秒级完成，是正确用法，
 * 不需要为了“看起来异步”而多绕一层。
 *
 * <h3>配置项</h3>
 *
 * <ul>
 *   <li>{@code connectTimeoutMs}（默认 5000）</li>
 *   <li>{@code readTimeoutMs} / {@code writeTimeoutMs}（默认 10000）</li>
 *   <li>{@code maxRequests}（默认 64，同时进行中的请求上限）</li>
 *   <li>{@code maxRequestsPerHost}（默认 16）</li>
 * </ul>
 */
public class HttpPlugin implements Component {

    public static final HttpPlugin INSTANCE = new HttpPlugin();

    private HttpPlugin() {
    }

    @Override
    public String getName() {
        return "http";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        long connectMs = number(config, "connectTimeoutMs", 5000);
        long ioMs = number(config, "readTimeoutMs", 10000);
        int maxRequests = (int) number(config, "maxRequests", 64);
        int maxPerHost = (int) number(config, "maxRequestsPerHost", 16);

        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(ioMs, TimeUnit.MILLISECONDS)
                .writeTimeout(ioMs, TimeUnit.MILLISECONDS)
                .build();
        client.dispatcher().setMaxRequests(maxRequests);
        client.dispatcher().setMaxRequestsPerHost(maxPerHost);

        // 构造即注册服务：Service 的构造函数里通过 ctx.provide 完成
        new HttpService(ctx, client);

        // 清理函数同样留在主循环：shutdown() 只是停止派发新任务（不等待、
        // 不中断在飞的请求），evictAll() 只关空闲连接，两者都是微秒级的
        // 队列/套接字操作，没有阻塞 IO——与 JDBC 的 Connection.close 不同。
        return () -> {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        };
    }

    private static long number(Map<String, Object> config, String key, long fallback) {
        Object v = config.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

}
