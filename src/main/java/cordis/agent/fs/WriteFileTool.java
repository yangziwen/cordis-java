package cordis.agent.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.fs.FileSystemService;
import cordis.fs.WriteResult;

/**
 * {@code write_file}：新建或整体覆盖一个文本文件。
 *
 * <p>危险动作，但它不自己设防——沙箱、大小上限、{@code ApprovalGate} 审批、
 * 原子落盘都在 {@link FileSystemService} 里；被拒绝时抛出，由 loop 转成
 * {@code {"error":...}} 回填，模型看得见拒绝原因。
 */
final class WriteFileTool implements Tool {

    private static final int PREVIEW = 120;

    private final FileSystemService fs;

    WriteFileTool(FileSystemService fs) {
        this.fs = fs;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "新建或整体覆盖一个文本文件（已有内容会被替换）。写入前会经过审批，"
                + "新文件必须写在工作区内已存在的目录里。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"目标文件路径，相对工作区根\"},"
                + "\"content\":{\"type\":\"string\",\"description\":\"完整的文件内容\"}},"
                + "\"required\":[\"path\",\"content\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JsonCodec.MAPPER.readTree(arguments);
        String path = args.path("path").asText("");
        String content = args.path("content").asText("");
        WriteResult result = fs.writeSync(path, content);

        ObjectNode out = JsonCodec.MAPPER.createObjectNode();
        out.put("action", result.action);
        out.put("path", result.path);
        out.put("created", result.created);
        out.put("bytes", result.bytes);
        out.put("contentHead", clip(content));
        return JsonCodec.MAPPER.writeValueAsString(out);
    }

    private static String clip(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW) + "…";
    }

}
