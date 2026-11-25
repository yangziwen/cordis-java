package cordis.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cordis.core.Context;
import cordis.core.Service;

/**
 * 技能目录服务（服务名 {@code skills}）。
 *
 * <p>两件事：
 * <ol>
 *   <li>{@link #systemSection()}：给 system 指令拼"技能清单"（名字 + 一句话
 *       描述）——常驻但极小，这是模型"发现自己该用哪个技能"的全部线索；</li>
 *   <li>{@link #bodySync(String, int)}：按名字读正文的一个字节窗口——
 *       正文可能几十 KB（实测中位约 14KB），一次性全量回填会瞬间吃掉
 *       数千 token 且永久留在对话历史里，所以按窗口续读。</li>
 * </ol>
 *
 * <h3>线程契约</h3>
 *
 * <ul>
 *   <li>{@code list / find / systemSection} 只读构造时的内存快照，
 *       任意线程（含主循环）可调——{@code systemSection} 正是被
 *       SystemProvider 在每轮模型调用前调用的；</li>
 *   <li>{@code bodySync} 是阻塞文件 IO，只允许在扩展池（worker）线程上
 *       调用——典型调用方是 use_skill 工具，它本来就跑在 worker 上。</li>
 * </ul>
 *
 * <p>扫描在 {@link SkillPlugin} 的 offload 阶段完成一次，实例只持快照：
 * 目录内容变更后需要重装插件（fiber reload 即重扫）才会生效。
 */
public class SkillService extends Service {

    /** 技能名 → 元数据；构造一次之后只读（快照） */
    private final List<Skill> skills;

    /** system 清单里每条 description 截到的字符数 */
    private final int maxDescriptionChars;

    /** 正文窗口的字节数 */
    private final int maxLoadBytes;

    public SkillService(Context ctx, List<Skill> skills,
                        int maxDescriptionChars, int maxLoadBytes) {
        super(ctx, "skills");
        List<Skill> sorted = new ArrayList<Skill>(skills);
        sorted.sort((a, b) -> a.name.compareTo(b.name));
        this.skills = Collections.unmodifiableList(sorted);
        this.maxDescriptionChars = maxDescriptionChars;
        this.maxLoadBytes = maxLoadBytes;
    }

    /** 全部技能（快照，按名字字典序） */
    public List<Skill> list() {
        return skills;
    }

    /** 按名字查技能；找不到返回 null */
    public Skill find(String name) {
        if (name == null) {
            return null;
        }
        for (Skill skill : skills) {
            if (skill.name.equals(name.trim())) {
                return skill;
            }
        }
        return null;
    }

    /**
     * 拼技能清单段（给 system 指令用）。
     *
     * <p>形态：
     * <pre>
     * # 可用技能（use_skill 按名称加载正文）
     * - markdown-writer: 输出符合团队规范的 Markdown 报告
     * </pre>
     * 无技能时返回空串而不是占位文本——system 段没必要教模型调用不存在的工具。
     */
    public String systemSection() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        section.append("# 可用技能（用 use_skill 按名称加载正文）\n");
        for (Skill skill : skills) {
            section.append("- ").append(skill.name).append(": ")
                    .append(clip(skill.description)).append('\n');
        }
        return section.toString();
    }

    /**
     * 读正文的一个字节窗口。{@code offset} 必须落在 {@code [0, totalBytes)}
     * 内；窗口越过文件末尾时按末尾收窄。窗口与总长都按字节计（见
     * {@link SkillSlice}）。
     *
     * <p>只允许在 worker 线程调用（阻塞 IO）。
     */
    public SkillSlice bodySync(String name, int offset) throws IOException {
        Skill skill = find(name);
        if (skill == null) {
            throw new IllegalArgumentException("unknown skill: " + name
                    + " (available: " + names() + ")");
        }
        if (offset < 0 || offset >= skill.totalBytes) {
            throw new IllegalArgumentException("offset " + offset + " out of range for skill \""
                    + skill.name + "\" (totalBytes=" + skill.totalBytes + ")");
        }
        if (Thread.currentThread() == ctx.scheduler.mainLoopThread()) {
            throw new IllegalStateException("SkillService.bodySync performs blocking file IO"
                    + " and must not run on the main loop");
        }
        int window = (int) Math.min(maxLoadBytes, skill.totalBytes - offset);
        byte[] raw = Files.readAllBytes(skill.file);      // SKILL.md 上限 256KB 量级，直接整读
        byte[] slice = Arrays.copyOfRange(raw, offset, offset + window);
        return new SkillSlice(skill.name, offset, window, skill.totalBytes,
                new String(slice, StandardCharsets.UTF_8));
    }

    /** 可用技能名列表（给适配层的自纠错提示用：名字拼错时告诉模型有哪些可逃） */
    public String names() {
        if (skills.isEmpty()) {
            return "(none)";
        }
        StringBuilder list = new StringBuilder();
        for (Skill skill : skills) {
            if (list.length() > 0) {
                list.append(", ");
            }
            list.append(skill.name);
        }
        return list.toString();
    }

    private String clip(String description) {
        if (description == null) {
            return "";
        }
        String flat = description.replace('\n', ' ').trim();
        return flat.length() <= maxDescriptionChars ? flat
                : flat.substring(0, maxDescriptionChars) + "…";
    }

    @Override
    public String toString() {
        return "SkillService(" + skills.size() + " skills)";
    }

}
