package cordis.agent.browser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import cordis.agent.Tool;
import cordis.agent.ToolService;
import cordis.browser.BrowserService;
import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;

/**
 * 浏览器工具套装：把 {@link BrowserService} 适配成 8 个 {@link Tool}
 * （open / snapshot / click / type / press / wait / screenshot / close），
 * 一次性挂进 {@code agent.tools}。组件名 {@code agent.browser}。
 *
 * <p>装配（能力层 {@code browser} 与本层依赖都要声明）：
 *
 * <pre>
 *   root.registry.use(BrowserPlugin.INSTANCE, Maps.of("policy", policy), null);
 *   root.registry.use(BrowserTools.INSTANCE,
 *           Maps.of(), Maps.of("browser", true, "agent.tools", true));
 * </pre>
 *
 * <p>工作方式：{@code browser_open} 返回带 {@code [ref=e12]} 的无障碍树，模型后续
 * 用 {@code browser_click(pageId, "e12")} / {@code browser_type(pageId, "e4", "北京")}
 * 操作，不需要自己写 CSS 选择器。点击 / 输入 / 按键都返回<b>操作后的新快照</b>，
 * 让模型一次调用就能看到结果，省一轮来回。
 */
public final class BrowserTools implements Component {

    public static final BrowserTools INSTANCE = new BrowserTools();

    private BrowserTools() {
    }

    @Override
    public String getName() {
        return "agent.browser";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        BrowserService browser = ctx.get("browser");
        if (browser == null) {
            throw new IllegalStateException("agent.browser tools loaded but browser service"
                    + " is not visible; declare inject {\"browser\": true} when use()ing it");
        }
        ToolService tools = ctx.get("agent.tools");
        if (tools == null) {
            throw new IllegalStateException("agent.browser tools loaded but agent.tools is not"
                    + " visible; declare inject {\"agent.tools\": true} when use()ing it");
        }
        List<Disposable> registrations = new ArrayList<>();
        for (Tool tool : Arrays.asList(
                new BrowserOpenTool(browser), new BrowserSnapshotTool(browser),
                new BrowserClickTool(browser), new BrowserTypeTool(browser),
                new BrowserPressTool(browser), new BrowserWaitTool(browser),
                new BrowserScreenshotTool(browser), new BrowserCloseTool(browser))) {
            registrations.add(tools.add(tool));
        }
        return () -> {
            for (Disposable registration : registrations) {
                registration.dispose();
            }
        };
    }

}
