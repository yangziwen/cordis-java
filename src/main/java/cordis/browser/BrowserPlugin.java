package cordis.browser;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.http.HttpAccessPolicy;

/**
 * 无头浏览器插件：装载 = 起 {@link BrowserService}（服务名 {@code browser}）。
 *
 * <h3>配置</h3>
 *
 * <ul>
 *   <li>{@code policy}（默认拒绝一切）：{@link HttpAccessPolicy} 实例，导航闸门；
 *       可以直接拿 {@code --allow-host} 那套规则，浏览器的导航和 http 工具
 *       共用同一个白名单；</li>
 *   <li>{@code screenshotDir}（默认 {@code screenshots}）：截图落盘目录；</li>
 *   <li>{@code headless}（默认 true）；</li>
 *   <li>{@code channel}（默认 {@code chrome}）：用系统安装的 Chrome 而不是
 *       Playwright 自带二进制。自带二进制的 headless shell 更容易被站点识别，
 *       而且要多下 300MB；机器上没有 Chrome 时把它设为空串即回退自带；</li>
 *   <li>{@code maxPages}（默认 8）：超过就关掉最久未用的页面；</li>
 *   <li>{@code timeoutMs}（默认 20000）：导航与元素操作超时；</li>
 *   <li>{@code snapshotChars}（默认 12000）：单次快照回填上限；</li>
 *   <li>{@code userAgent}：默认常见 Chrome UA。</li>
 * </ul>
 *
 * <p>装载后会<b>异步预热</b>（driver 约 9s + 启动浏览器约 4s），不阻塞主循环；
 * 预热失败只打印一行日志，等真正调用工具时再报错——浏览器装不上不该让整个
 * server 起不来。
 */
public class BrowserPlugin implements Component {

    public static final BrowserPlugin INSTANCE = new BrowserPlugin();

    /** 预热前的宽限期：让「刚装配就关闭」的场景直接跳过预热 */
    private static final long PREWARM_GRACE_MS = 200;

    private BrowserPlugin() {
    }

    @Override
    public String getName() {
        return "browser";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        final HttpAccessPolicy policy = config.get("policy") instanceof HttpAccessPolicy
                ? (HttpAccessPolicy) config.get("policy")
                : HttpAccessPolicy.denyAll();
        final String dir = stringConfig(config, "screenshotDir", "screenshots");
        final BrowserService service = new BrowserService(ctx, policy, Paths.get(dir),
                intConfig(config, "maxPages", BrowserService.DEFAULT_MAX_PAGES),
                intConfig(config, "timeoutMs", BrowserService.DEFAULT_TIMEOUT_MS),
                intConfig(config, "snapshotChars", BrowserService.DEFAULT_SNAPSHOT_CHARS),
                booleanConfig(config, "headless", true),
                stringConfig(config, "channel", "chrome"),
                config.get("userAgent") == null ? null : String.valueOf(config.get("userAgent")));

        ctx.scheduler.<Boolean>offload(() -> {
            // 给「装配完立刻关闭」的场景一个干净的退出点：预热共 13s，而关闭
            // 往往在几十毫秒内就发生，先把 driver 拉到一半再被掐断只会留下脏
            // node 进程和一大段栈
            try {
                Thread.sleep(PREWARM_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Boolean.TRUE;
            }
            if (service.isClosed()) {
                return Boolean.TRUE;
            }
            try {
                service.prewarmSync();
                System.out.println("[browser] prewarmed: " + service);
            } catch (Throwable t) {
                if (!service.isClosed()) {
                    System.out.println("[browser] prewarm failed (tools will retry on demand): "
                            + t.getMessage());
                }
            }
            return Boolean.TRUE;
        });

        return () -> {
            System.out.println("[browser] closing driver");
            ctx.scheduler.<Boolean>offload(() -> {
                service.shutdownSync();
                System.out.println("[browser] driver closed");
                return Boolean.TRUE;
            });
        };
    }

    private static String stringConfig(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static int intConfig(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("config \"" + key + "\" is not a number: "
                        + value);
            }
        }
        return fallback;
    }

    private static boolean booleanConfig(Map<String, Object> config, String key,
                                         boolean fallback) {
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return fallback;
    }

}
