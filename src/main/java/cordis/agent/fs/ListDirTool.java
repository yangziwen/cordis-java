package cordis.agent.fs;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.fs.FileEntry;
import cordis.fs.FileSystemService;

/**
 * {@code list_dir}：列出工作区里一个目录的内容（不递归）。
 *
 * <p>沙箱、大小、排序都由 {@link FileSystemService} 负责；本类只做协议塑形。
 * 适配层可以直接调 {@code *Sync} 变体：{@code Tool.run} 本来就跑在 worker /
 * toolPool 上，再 offload 一次只是白占一个线程。
 */
final class ListDirTool implements Tool {

    private final FileSystemService fs;

    ListDirTool(FileSystemService fs) {
        this.fs = fs;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "列出指定目录下的文件和子目录（不递归）。路径必须在工作区内，"
                + "相对路径相对工作区根。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"目录路径，默认工作区根\"}},"
                + "\"required\":[]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JsonCodec.MAPPER.readTree(arguments);
        String path = args.path("path").asText(".");
        List<FileEntry> entries = fs.listSync(path);

        ObjectNode out = JsonCodec.MAPPER.createObjectNode();
        out.put("path", path);
        ArrayNode array = out.putArray("entries");
        for (FileEntry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("name", entry.name);
            node.put("type", entry.directory ? "dir" : "file");
            node.put("sizeBytes", entry.sizeBytes);
        }
        out.put("count", entries.size());
        return JsonCodec.MAPPER.writeValueAsString(out);
    }

}
