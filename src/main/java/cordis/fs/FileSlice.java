package cordis.fs;

import java.util.List;

/**
 * 一次读文件的结果（{@link FileSystemService#readSync}）。
 *
 * <p>按行返回而不是整段字符串：行号是 agent 场景的一等公民——模型引用
 * 「第 N 行」、edit 描述位置，都建立在行这个单位上。行内容<b>不含</b>换行符。
 */
public final class FileSlice {

    /** 文件（沙箱内的绝对真实路径） */
    public final String path;

    /** 本切片第一行的行号（1 基） */
    public final int startLine;

    /** 读到的行（不含换行符，可能为空表示空文件或超出了文件末尾） */
    public final List<String> lines;

    /** 文件总行数；文件太大没数完时为 -1（调用方不要把它当成精确值用） */
    public final int totalLines;

    public FileSlice(String path, int startLine, List<String> lines, int totalLines) {
        this.path = path;
        this.startLine = startLine;
        this.lines = lines;
        this.totalLines = totalLines;
    }

    @Override
    public String toString() {
        return "FileSlice(" + path + ", lines " + startLine + ".."
                + (startLine + lines.size() - 1) + " of " + totalLines + ")";
    }

}
