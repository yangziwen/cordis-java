package cordis.agent;

import java.util.Map;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;

/**
 * 把 {@link Tool} 适配成可热插拔的组件：装载 = 注册，卸载 = 反注册。
 *
 * <p>标准装配（依赖声明走 inject，agent 未就位时本 fiber 自动等待）：
 *
 * <pre>
 *   Fiber mathFiber = root.registry.use(new ToolComponent(new MathTool()),
 *           Maps.of(), Maps.of("agent.tools", true));
 * </pre>
 *
 * <pre>
 *   // 移除工具（agent 下一轮的快照里它就消失了）：
 *   mathFiber.dispose();
 * </pre>
 *
 * <p>apply 里 {@code ctx.get("agent.tools")} 能拿到服务，靠的正是 inject
 * 声明：依赖未满足时 fiber 不进入 apply；满足时提供者必然 ACTIVE。
 */
public final class ToolComponent implements Component {

    private final Tool tool;

    public ToolComponent(Tool tool) {
        this.tool = tool;
    }

    @Override
    public String getName() {
        return "tool:" + tool.name();
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        ToolService tools = ctx.get("agent.tools");
        if (tools == null) {
            // 正常装配不会走到这里（inject 未满足时 fiber 停在 PENDING）；
            // 出现说明调用方绕过了 inject 手动 use，给出可定位的提示
            throw new IllegalStateException("tool \"" + tool.name()
                    + "\" loaded but agent.tools is not visible; "
                    + "declare inject {\"agent.tools\": true} when use()ing it");
        }
        return tools.add(tool);
    }

}
