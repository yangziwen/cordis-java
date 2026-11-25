package cordis.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Promise;

/**
 * 文件系统插件（<b>异步初始化版</b>，与 {@code SqlitePlugin} 同款两段式）：
 *
 * <ol>
 *   <li>阻塞的磁盘操作（root 不存在时建目录、{@code toRealPath()} 归一化）
 *       经 {@code offload} 投到扩展池——期间 fiber 停在 LOADING，主循环零阻塞；</li>
 *   <li>回调回主循环构造 {@link FileSystemService}（provide 必须在主循环）；</li>
 *   <li>清理函数无事可做：本服务不持有句柄，每次操作都即开即关。</li>
 * </ol>
 *
 * <h3>配置</h3>
 *
 * <ul>
 *   <li>{@code root}（必填）：工作区根目录，沙箱边界；</li>
 *   <li>{@code createRoot}（默认 true）：root 不存在时创建；</li>
 *   <li>{@code maxReadBytes}（默认 2MB）：单文件读取上限；</li>
 *   <li>{@code maxWriteBytes}（默认 1MB）：单次写入上限；</li>
 *   <li>{@code approval}（默认拒绝一切）：{@link ApprovalGate} 实例。</li>
 * </ul>
 */
public class FileSystemPlugin implements Component {

    /** 默认单文件读取上限：2MB */
    public static final long DEFAULT_MAX_READ_BYTES = 2 * 1024 * 1024;

    /** 默认单次写入上限：1MB */
    public static final long DEFAULT_MAX_WRITE_BYTES = 1024 * 1024;

    public static final FileSystemPlugin INSTANCE = new FileSystemPlugin();

    private FileSystemPlugin() {
    }

    @Override
    public String getName() {
        return "filesystem";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        throw new UnsupportedOperationException("本插件只走 applyAsync（异步准备 root）");
    }

    @Override
    public Promise<Disposable> applyAsync(Context ctx, Map<String, Object> config) {
        Object rootConfig = config.get("root");
        if (rootConfig == null || String.valueOf(rootConfig).trim().isEmpty()) {
            return Promise.rejected(ctx.scheduler,
                    new IllegalArgumentException("filesystem plugin requires config \"root\""
                            + " (the sandbox boundary)"));
        }
        final long maxReadBytes = longConfig(config, "maxReadBytes", DEFAULT_MAX_READ_BYTES);
        final long maxWriteBytes = longConfig(config, "maxWriteBytes", DEFAULT_MAX_WRITE_BYTES);
        final boolean createRoot = booleanConfig(config, "createRoot", true);
        final ApprovalGate approval = config.get("approval") instanceof ApprovalGate
                ? (ApprovalGate) config.get("approval")
                : ApprovalGate.denyAll();

        return ctx.scheduler.<Path>offload(() -> {
            Path raw = Paths.get(String.valueOf(rootConfig).trim());
            if (!Files.exists(raw) && createRoot) {
                Files.createDirectories(raw);
            }
            if (!Files.isDirectory(raw)) {
                throw new IllegalStateException("workspace root is not a directory: " + raw);
            }
            Path realRoot = raw.toRealPath();
            System.out.println("[filesystem] workspace ready: " + realRoot
                    + "  (on " + Thread.currentThread().getName() + ")");
            return realRoot;
        }).then(realRoot -> new FileSystemService(ctx, realRoot,
                maxReadBytes, maxWriteBytes, approval)
        ).then(service -> (Disposable) () -> {
            // 无句柄可关：即开即关的服务，卸载只需让实例随 fiber 一起消失
        });
    }

    private static long longConfig(Map<String, Object> config, String key, long fallback) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
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
