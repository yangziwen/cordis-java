package cordis.agent;

import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Promise;
import cordis.sqlite.SqliteService;

/**
 * 记忆插件：建表 + 注册 {@link MemoryService}。
 *
 * <p>为什么要走 {@link #applyAsync}：DDL 是阻塞 JDBC，而 Service 构造必须
 * 在主循环（{@code ctx.provide} 会断言），两者不能在同一线程完成。于是复用
 * {@code SqlitePlugin} 的两段式套路：
 *
 * <ol>
 *   <li>{@code offload} 到扩展池执行 {@code CREATE TABLE IF NOT EXISTS}；</li>
 *   <li>{@code then} 回调回到主循环，此时构造 Service 并自动注册。</li>
 * </ol>
 *
 * <p>{@code then} 的回调线程不是巧合——{@code Promise} 的语义保证所有
 * 回调都在主循环上执行（见 {@code cordis.core.Promise} 的类注释）。
 *
 * <p>配置项：无（表结构固定；将来需要版本迁移时在此加 {@code schemaVersion}）。
 */
public class MemoryPlugin implements Component {

    public static final MemoryPlugin INSTANCE = new MemoryPlugin();

    private MemoryPlugin() {
    }

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        throw new UnsupportedOperationException("本插件只走 applyAsync（建表是阻塞 JDBC）");
    }

    @Override
    public Promise<Disposable> applyAsync(final Context ctx, Map<String, Object> config) {
        final SqliteService db = ctx.get("sqlite");
        if (db == null) {
            throw new IllegalStateException("memory requires \"sqlite\"; "
                    + "declare inject {\"sqlite\": true} when use()ing it");
        }
        return ctx.scheduler.offload(() -> {
            MemoryService.createSchema(db);
            return null;
        }).then(v -> {
            // 主循环：provide 与依赖唤醒都要求在这个线程上
            new MemoryService(ctx, db);
            return (Disposable) () -> {
            };
        });
    }

}
