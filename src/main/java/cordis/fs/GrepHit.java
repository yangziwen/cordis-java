package cordis.fs;

/**
 * grep 的一个命中（{@link FileSystemService#grepSync}）。
 *
 * <p>{@code text} 是命中所在的原始行（截断到 200 字符，两端已 trim），
 * 行号 1 基——和编辑器、和 {@link FileSlice} 的行号语义一致。
 */
public final class GrepHit {

    /** 命中所在文件（沙箱内的绝对真实路径） */
    public final String path;

    /** 行号（1 基） */
    public final int line;

    /** 命中行的文本（已截断） */
    public final String text;

    public GrepHit(String path, int line, String text) {
        this.path = path;
        this.line = line;
        this.text = text;
    }

    @Override
    public String toString() {
        return path + ":" + line + ": " + text;
    }

}
