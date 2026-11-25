package cordis.agent.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.fs.FileSlice;
import cordis.fs.FileSystemService;

/**
 * {@code read_file}：读取文本文件的一段（按行号区间）。
 *
 * <p>行以数组返回而不是拼成一个大字符串：模型引用行号、后续 edit 描述位置
 * 都更稳，也避免「截断发生在行中间」造成的歧义。行号从 1 开始，与
 * {@code FileSlice} / grep 的语义一致。
 */
final class ReadFileTool implements Tool {

    private final FileSystemService fs;

    ReadFileTool(FileSystemService fs) {
        this.fs = fs;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取文本文件的一段。offset 是 1 基行号，lines 是最多读的行数"
                + "（默认 30）。返回的行不含换行符。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"文件路径，相对工作区根\"},"
                + "\"offset\":{\"type\":\"integer\",\"description\":\"起始行号（1 基），默认 1\"},"
                + "\"lines\":{\"type\":\"integer\",\"description\":\"最多读多少行，默认 30\"}},"
                + "\"required\":[\"path\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = JsonCodec.MAPPER.readTree(arguments);
        String path = args.path("path").asText("");
        int offset = Math.max(args.path("offset").asInt(1), 1);
        int lines = Math.max(args.path("lines").asInt(30), 1);
        FileSlice slice = fs.readSync(path, offset, lines);

        ObjectNode out = JsonCodec.MAPPER.createObjectNode();
        out.put("path", slice.path);
        out.put("startLine", slice.startLine);
        out.put("totalLines", slice.totalLines);
        ArrayNode array = out.putArray("lines");
        for (String line : slice.lines) {
            array.add(line);
        }
        return JsonCodec.MAPPER.writeValueAsString(out);
    }

}
