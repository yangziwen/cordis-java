package cordis.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cordis.agent.Tool;
import cordis.skill.SkillService;
import cordis.skill.SkillSlice;

/**
 * use_skill 工具（包私有，经 {@link SkillTools} 注册）：
 * 按名字加载一个技能的正文窗口。
 *
 * <p>契约设计：
 * <ul>
 *   <li><b>清单在 system、正文在工具</b>——模型从 system 段的技能清单里
 *       选名字，本工具只负责取正文。名字拼错不抛异常，回退为"可用技能
 *       列表"文本：让模型自己改道，比中断 loop 便宜；</li>
 *   <li><b>按窗口续读</b>——返回头标注字节区间，截断时尾注给出
 *       {@code offset} 续读方式。中位真实 skill 约 14.5KB，一次性全量
 *       回填会吃掉数千 token 且永久留在历史里；</li>
 *   <li><b>JSON 解析用 Jackson</b>——本包是 agent 下的适配层（与
 *       {@code agent.fs}、{@code agent.llm} 一样），Jackson 只出现在适配层。</li>
 * </ul>
 */
final class UseSkillTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillService skills;

    UseSkillTool(SkillService skills) {
        this.skills = skills;
    }

    @Override
    public String name() {
        return "use_skill";
    }

    @Override
    public String description() {
        return "加载一个技能（skill）的操作指引正文。技能名与用途见 system 指令里的"
                + "技能清单；正文较长会按窗口返回，按尾注提示带 offset 续读。"
                + "判断任务匹配某个技能时必须先加载正文再操作。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\",\"description\":\"技能名，来自技能清单\"},"
                + "\"offset\":{\"type\":\"integer\",\"description\":\"续读起始字节，首次加载传 0 或省略\"}"
                + "},\"required\":[\"name\"]}";
    }

    @Override
    public String run(String arguments) {
        String name;
        int offset = 0;
        try {
            JsonNode node = JSON.readTree(arguments == null ? "{}" : arguments);
            JsonNode nameNode = node.get("name");
            if (nameNode == null || nameNode.asText().trim().isEmpty()) {
                return "missing required parameter \"name\"; available skills: "
                        + skills.names();
            }
            name = nameNode.asText().trim();
            JsonNode offsetNode = node.get("offset");
            if (offsetNode != null && offsetNode.canConvertToInt()) {
                offset = offsetNode.asInt();
            }
        } catch (Exception e) {
            return "failed to parse use_skill arguments: " + e;
        }
        try {
            return render(skills.bodySync(name, offset));
        } catch (IllegalArgumentException e) {
            // 名字拼错 / offset 越界：把可自纠的信息原样给模型
            return e.getMessage();
        } catch (Exception e) {
            return "failed to read skill \"" + name + "\": " + e;
        }
    }

    private static String render(SkillSlice slice) {
        StringBuilder out = new StringBuilder();
        out.append("[skill: ").append(slice.skill).append(", bytes ")
                .append(slice.offset).append("..").append(slice.offset + slice.length)
                .append(" of ").append(slice.totalBytes).append("]\n");
        out.append(slice.text);
        if (slice.truncated()) {
            out.append("\n[truncated: ").append(slice.totalBytes - slice.offset - slice.length)
                    .append(" bytes left; call use_skill again with name and offset=")
                    .append(slice.offset + slice.length).append(" to continue]");
        }
        return out.toString();
    }

}
