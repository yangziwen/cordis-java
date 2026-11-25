package cordis.agent.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.fs.FileSystemService;
import cordis.fs.WriteResult;

/**
 * {@code edit_file}：精确替换文本文件中的一段。
 *
 * <p>oldText 必须在文件里<b>恰好出现一次</b>——0 次或多次都会被
 * {@link FileSystemService} 拒绝。这是刻意设计的：位置含糊时模型宁可先
 * read_file 再改，也不要让它猜一个大概位置。
 */
final class EditFileTool implements Tool {

    private static final int PREVIEW = 120;

    private final FileSystemService fs;

    EditFileTool(FileSystemService fs) {
        this.fs = fs;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "把文件中恰好一处出现的 oldText 替换成 newText。oldText 必须与文件"
                + "内容逐字符一致且只出现一次（出现多次会拒绝，把 oldText 写得更长"
                + "再试）。先 read_file 确认原文。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"目标文件路径，相对工作区根\"},"
                + "\"oldText\":{\"type\":\"string\",\"description\":\"要被替换的原文（逐字符一致）\"},"
                + "\"newText\":{\"type\":\"string\",\"description\":\"替换后的新文本\"}},"
                + "\"required\":[\"path\",\"oldText\",\"newText\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JsonCodec.MAPPER.readTree(arguments);
        String path = args.path("path").asText("");
        String oldText = args.path("oldText").asText("");
        String newText = args.path("newText").asText("");
        WriteResult result = fs.editSync(path, oldText, newText);

        ObjectNode out = JsonCodec.MAPPER.createObjectNode();
        out.put("action", result.action);
        out.put("path", result.path);
        out.put("bytes", result.bytes);
        ObjectNode replaced = out.putObject("replaced");
        replaced.put("old", clip(oldText));
        replaced.put("new", clip(newText));
        return JsonCodec.MAPPER.writeValueAsString(out);
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW) + "…";
    }

}
