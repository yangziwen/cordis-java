package cordis.agent.fs;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.fs.FileSystemService;
import cordis.fs.GrepHit;

/**
 * {@code grep}：在工作区里按正则找文本，返回 {@code path:行号:行内容}。
 *
 * <p>模型「先找再改」的主要入口：定位到文件与行号之后，用 read_file 看上下文、
 * 用 edit_file 精确替换。跳过 .git / target / node_modules 等目录的策略在
 * 能力层。
 */
final class GrepTool implements Tool {

    private final FileSystemService fs;

    GrepTool(FileSystemService fs) {
        this.fs = fs;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在工作区全部文本文件里按正则搜索（找到即命中，不是整行匹配）。"
                + "返回命中所在文件、行号和该行内容。glob 可按文件名过滤，"
                + "如 *.java。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"pattern\":{\"type\":\"string\",\"description\":\"java 正则表达式\"},"
                + "\"glob\":{\"type\":\"string\",\"description\":\"文件名过滤，如 *.java，可选\"}},"
                + "\"required\":[\"pattern\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JsonCodec.MAPPER.readTree(arguments);
        String pattern = args.path("pattern").asText("");
        String glob = args.path("glob").asText(null);
        List<GrepHit> hits = fs.grepSync(pattern, glob);

        ObjectNode out = JsonCodec.MAPPER.createObjectNode();
        ArrayNode array = out.putArray("hits");
        for (GrepHit hit : hits) {
            ObjectNode node = array.addObject();
            node.put("path", hit.path);
            node.put("line", hit.line);
            node.put("text", hit.text);
        }
        out.put("count", hits.size());
        return JsonCodec.MAPPER.writeValueAsString(out);
    }

}
