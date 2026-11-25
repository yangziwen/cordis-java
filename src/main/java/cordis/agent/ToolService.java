package cordis.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Service;

/**
 * 工具注册表（服务名 {@code agent.tools}）。
 *
 * <p>注册 / 反注册使用 add 返回的 {@link Disposable}，而它通常被
 * {@code ToolComponent} 交给 fiber 当 effect 持有——于是"一个工具 = 一个
 * fiber"：fiber dispose（热卸载）时工具自动消失，重新 use 时自动回来，
 * AgentService 下一轮拿到的快照随之变化。工具集的动态性完全复用 cordis
 * 的生命周期模型，不需要额外机制。
 *
 * <p>实现使用 {@code ConcurrentHashMap}：add 发生在装载它的 fiber 的
 * apply（主循环）里，而 snapshot 可能被扩展池上的 AgentService 读取——
 * 读写天然跨线程。
 */
public final class ToolService extends Service {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolService(Context ctx) {
        super(ctx, "agent.tools");
    }

    /**
     * 注册工具；返回反注册句柄。
     *
     * @throws IllegalStateException 同名工具已注册——名字是模型请求的唯一
     *                               键，静默替换会让两个 fiber 各自以为自己的
     *                               工具在线上，故必须 fail-fast
     */
    public Disposable add(final Tool tool) {
        Tool prev = tools.putIfAbsent(tool.name(), tool);
        if (prev != null) {
            throw new IllegalStateException("tool \"" + tool.name()
                    + "\" has already been registered");
        }
        return () -> tools.remove(tool.name(), tool);
    }

    /**
     * 当前可用工具的快照，按名字排序——顺序稳定意味着发给模型的
     * function 列表逐字节一致，便于缓存与对比。
     */
    public List<Tool> snapshot() {
        List<Tool> list = new ArrayList<>(tools.values());
        list.sort((a, b) -> a.name().compareTo(b.name()));
        return Collections.unmodifiableList(list);
    }

    /** 是否已注册指定工具（诊断用） */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    @Override
    public String toString() {
        return "ToolService(" + tools.keySet() + ")";
    }

}
