package cordis.agent.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import cordis.agent.Tool;
import cordis.agent.ToolService;
import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.http.HttpService;
import okhttp3.OkHttpClient;

/**
 * HTTP 工具套装：把 {@link HttpService} 适配成 {@code http_get} /
 * {@code http_post} 两个 {@link Tool}，一次性挂进 {@code agent.tools}。
 *
 * <p>与 {@code agent.fs} / {@code agent.skill} 对称：能力在
 * {@code cordis.http}（平级能力包），协议适配在 {@code cordis.agent.http}。
 *
 * <pre>
 *   root.registry.use(HttpPlugin.INSTANCE, Maps.of(), null);
 *   root.registry.use(HttpTools.INSTANCE,
 *           Maps.of("policy", HttpAccessPolicy.hosts("api.example.com")),
 *           Maps.of("http", true, "agent.tools", true));
 * </pre>
 *
 * <h3>配置项</h3>
 *
 * <ul>
 *   <li>{@code policy}（默认 {@link HttpAccessPolicy#denyAll()}）能访问哪些
 *       主机；<b>每一跳重定向都会重新校验</b>；</li>
 *   <li>{@code timeoutMs}（默认 15000）连接/读/整体调用超时；</li>
 *   <li>{@code maxResponseBytes}（默认 65536）单次响应最多回填给模型的字节数，
 *       超出标记 {@code truncated}；</li>
 *   <li>{@code maxRedirects}（默认 3）手动跟随的重定向跳数上限。</li>
 * </ul>
 *
 * <p>不使用 {@link HttpService#client()} 的默认重定向行为：{@code followRedirects
 * (false)} 才能逐跳过策略（理由见 {@link HttpCaller}）。超时也在这里覆盖，
 * 避免某个慢接口把一次对话拖到平台默认的 10s 以上仍不结束。
 */
public final class HttpTools implements Component {

    public static final HttpTools INSTANCE = new HttpTools();

    private HttpTools() {
    }

    @Override
    public String getName() {
        return "agent.http";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        HttpService http = ctx.get("http");
        if (http == null) {
            throw new IllegalStateException("agent.http tools loaded but http service"
                    + " is not visible; declare inject {\"http\": true} when use()ing it");
        }
        ToolService tools = ctx.get("agent.tools");
        if (tools == null) {
            throw new IllegalStateException("agent.http tools loaded but agent.tools is not"
                    + " visible; declare inject {\"agent.tools\": true} when use()ing it");
        }
        HttpAccessPolicy policy = config.get("policy") instanceof HttpAccessPolicy
                ? (HttpAccessPolicy) config.get("policy") : HttpAccessPolicy.denyAll();
        int timeoutMs = (int) number(config, "timeoutMs", 15000);
        int maxBytes = (int) number(config, "maxResponseBytes", 65536);
        int maxRedirects = (int) number(config, "maxRedirects", 3);

        OkHttpClient client = http.client().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        final HttpCaller caller = new HttpCaller(client, policy, maxBytes, maxRedirects);

        List<Disposable> registrations = new ArrayList<Disposable>();
        for (Tool tool : Arrays.asList(new HttpGetTool(caller), new HttpPostTool(caller))) {
            registrations.add(tools.add(tool));
        }
        // 卸载 = 两个工具一起从注册表摘掉（热插拔语义与其他 ToolComponent 一致）
        return () -> {
            for (Disposable registration : registrations) {
                registration.dispose();
            }
        };
    }

    private static long number(Map<String, Object> config, String key, long fallback) {
        Object value = config.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

}
