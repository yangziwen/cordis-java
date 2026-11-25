package cordis.app;

import java.nio.file.Path;

/**
 * 工作区初始化钩子：{@link AgentServer} 在装配能力之前调用，用于预置
 * 模板技能目录、素材文件等。
 *
 * <p>只在 {@code start()} 时调用一次；是否覆盖已有文件由实现自己决定
 * （示例见 {@code cordis.demo.AgentServerDemo}，它在 skills 目录非空时
 * 直接跳过，避免覆盖使用者改过的技能）。
 */
public interface WorkspaceInitializer {

    /**
     * @param root      工作区根目录（已创建）
     * @param skillsDir 技能目录（已创建），通常等于 {@code root/skills}
     */
    void init(Path root, Path skillsDir) throws Exception;

}
