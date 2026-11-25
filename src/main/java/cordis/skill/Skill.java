package cordis.skill;

import java.nio.file.Path;

/**
 * 一个可供 agent 按需加载的技能（capability 层的值对象）。
 *
 * <p>对应 CatPaw / Claude Code 的 skill 形态：一个目录、一份 {@code SKILL.md}
 * 正文、可选的 {@code skill.json} 元数据。目录名是注册键（稳定、可做文件名），
 * {@code skill.json} 里的 {@code description} 是给模型看的触发线索——它会被
 * 拼进 system 清单，但正文不会：正文按需加载（见
 * {@link SkillService#bodySync}），这是 skill 机制省 token 的全部要点。
 *
 * <p>本类持有的是扫描时的一次快照；skill 目录后续的增删改不会反映到
 * 已加载的实例——变更生效的方式是重装 {@code SkillPlugin}（fiber reload
 * 即重扫），这正是复用 cordis 热插拔能力的部分。
 */
public final class Skill {

    /** 技能名 = 所在目录名（注册键，全局唯一） */
    public final String name;

    /** 给模型看的一句话描述：skill.json 的 description，缺省退化为正文首个非空行 */
    public final String description;

    /** SKILL.md 的绝对真实路径 */
    public final Path file;

    /** SKILL.md 的总字节数（正文窗口与续读提示都按它计算） */
    public final long totalBytes;

    public Skill(String name, String description, Path file, long totalBytes) {
        this.name = name;
        this.description = description;
        this.file = file;
        this.totalBytes = totalBytes;
    }

    @Override
    public String toString() {
        return "Skill(" + name + ", " + totalBytes + "B)";
    }

}
