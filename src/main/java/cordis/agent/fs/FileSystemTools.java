package cordis.agent.fs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import cordis.agent.Tool;
import cordis.agent.ToolService;
import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.fs.FileSystemService;

/**
 * 文件系统工具套装：把 {@link FileSystemService} 能力适配成 5 个
 * {@link Tool}（list_dir / read_file / write_file / edit_file / grep），
 * 一次性挂进 {@code agent.tools}。
 *
 * <p>为什么不逐个注册（像 demo 里的 ToolComponent）：这五个工具是一个整体
 * ——「模型看得到的工作区视图」，拆开注册只会让装配代码重复五遍。
 * 组件名 {@code agent.fs}；依赖声明（inject）由调用方在 use 时给出：
 *
 * <pre>
 *   root.registry.use(FileSystemTools.INSTANCE,
 *           Maps.of(), Maps.of("filesystem", true, "agent.tools", true))
 * </pre>
 *
 * <p>依赖方向：本包 → {@code cordis.fs}（能力）+ {@code cordis.agent}
 * （Tool SPI）。与 {@code cordis.agent.llm} → {@code cordis.http} 的结构
 * 完全对称：能力在底层平级包，协议适配在 agent 下。
 */
public final class FileSystemTools implements Component {

    public static final FileSystemTools INSTANCE = new FileSystemTools();

    private FileSystemTools() {
    }

    @Override
    public String getName() {
        return "agent.fs";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        FileSystemService fs = ctx.get("filesystem");
        if (fs == null) {
            throw new IllegalStateException("agent.fs tools loaded but filesystem service"
                    + " is not visible; declare inject {\"filesystem\": true} when use()ing it");
        }
        ToolService tools = ctx.get("agent.tools");
        if (tools == null) {
            throw new IllegalStateException("agent.fs tools loaded but agent.tools is not"
                    + " visible; declare inject {\"agent.tools\": true} when use()ing it");
        }
        List<Disposable> registrations = new ArrayList<>();
        for (Tool tool : Arrays.asList(
                new ListDirTool(fs), new ReadFileTool(fs), new WriteFileTool(fs),
                new EditFileTool(fs), new GrepTool(fs))) {
            registrations.add(tools.add(tool));
        }
        // 卸载 = 五个工具全部从注册表摘掉（热插拔语义与其他 ToolComponent 一致）
        return () -> {
            for (Disposable registration : registrations) {
                registration.dispose();
            }
        };
    }

}
