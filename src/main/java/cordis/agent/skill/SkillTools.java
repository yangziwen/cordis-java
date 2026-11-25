package cordis.agent.skill;

import java.util.Map;

import cordis.agent.Tool;
import cordis.agent.ToolService;
import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.skill.SkillService;

/**
 * 技能工具套装：把 {@link SkillService} 适配成 {@code use_skill} 工具挂进
 * {@code agent.tools}。组件名 {@code agent.skills}；依赖（inject）由调用方
 * 在 use 时声明：
 *
 * <pre>
 *   root.registry.use(SkillTools.INSTANCE,
 *           Maps.of(), Maps.of("skills", true, "agent.tools", true))
 * </pre>
 *
 * <p>与 {@code FileSystemTools}（agent.fs → fs + agent）结构完全对称：
 * 能力在平级包 {@code cordis.skill}，协议适配在 agent 下。清单进 system
 * （调用方拿 {@code SkillService#systemSection()} 自行拼 SystemProvider）、
 * 正文进工具——两半各归其位。
 *
 * <p>只有当 {@code skills} 与 {@code agent.tools} 两个服务都就绪时本组件
 * 才会生效（依赖级联），于是 agent 卸载时 use_skill 自动消失、agent 回来
 * 时自动重注册——与单个 ToolComponent 的热插拔语义一致。
 */
public final class SkillTools implements Component {

    public static final SkillTools INSTANCE = new SkillTools();

    private SkillTools() {
    }

    @Override
    public String getName() {
        return "agent.skills";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        SkillService skills = ctx.get("skills");
        if (skills == null) {
            throw new IllegalStateException("agent.skills tools loaded but skills service is"
                    + " not visible; declare inject {\"skills\": true} when use()ing it");
        }
        ToolService tools = ctx.get("agent.tools");
        if (tools == null) {
            throw new IllegalStateException("agent.skills tools loaded but agent.tools is not"
                    + " visible; declare inject {\"agent.tools\": true} when use()ing it");
        }
        Disposable registration = tools.add(new UseSkillTool(skills));
        // 卸载 = use_skill 从注册表摘掉；SkillService 本身随 skills fiber 卸载
        return registration::dispose;
    }

}
