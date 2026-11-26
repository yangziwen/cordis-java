package cordis.skill;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能元数据：技能目录下的 {@code skill.json}（包私有）。
 *
 * <pre>
 * markdown-writer/
 *   skill.json    {"description": "团队 Markdown 报告的格式规范"}
 *   SKILL.md      正文（按需加载）
 * </pre>
 *
 * <p>只用 {@code description} 一个字段：技能名是目录名（稳定、可做文件名），
 * 不需要元数据里再写一遍。
 *
 * <p>容错策略是<b>宽容</b>而非 fail-fast：元数据缺失或坏掉只会损失
 * description（退化到正文首个非空行），不该让整个技能目录加载失败——
 * 一个写坏的 skill.json 不该拖垮其余几十个技能。代价是问题会以警告形式
 * 出现在 stderr 上，需要人工留意。
 *
 * <p>依赖取舍：{@code cordis.skill} 其余部分零第三方依赖，但元数据是
 * 结构化文本，直接复用项目已有的 Jackson，不新增依赖，也不手写 JSON 解析器。
 */
final class SkillMeta {

    private static final Logger LOG = LoggerFactory.getLogger(SkillMeta.class);

    /** 元数据文件名（与 SKILL.md 并列在技能目录里） */
    static final String FILE = "skill.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SkillMeta() {
    }

    /**
     * 读 {@code <dir>/skill.json} 的 description。
     *
     * @return 描述文本；文件不存在 / 无 description / 解析失败时返回 null
     *         （解析失败已打印警告）
     */
    static String description(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject()) {
                warn(file, "expected a JSON object");
                return null;
            }
            JsonNode description = root.get("description");
            if (description == null || description.isNull()) {
                return null;
            }
            if (!description.isTextual()) {
                warn(file, "\"description\" must be a string");
                return null;
            }
            String text = description.asText().trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            warn(file, String.valueOf(e.getMessage()));
            return null;
        }
    }

    private static void warn(Path file, String reason) {
        String flat = String.valueOf(reason).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (flat.length() > 120) {
            flat = flat.substring(0, 120) + "…";
        }
        LOG.warn("ignoring malformed {} at {} ({}); falling back to the first line of SKILL.md",
                FILE, file, flat);
    }

}
