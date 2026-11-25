package cordis.skill;

/**
 * 一次技能正文窗口读取（{@link SkillService#bodySync}）的结果。
 *
 * <p>窗口以<b>字节</b>为单位（与 {@code totalBytes} 同一坐标系）；字节边界
 * 可能切断多字节 UTF-8 字符，解码时尾部残缺字符会替换为 U+FFFD——
 * 续读拼接时以 offset 为准而不是以文本对齐，个别接缝字符会重复/替换，
 * 对模型理解正文无碍。
 */
public final class SkillSlice {

    /** 技能名 */
    public final String skill;

    /** 本窗口起始字节（含） */
    public final int offset;

    /** 本窗口字节数（不含） */
    public final int length;

    /** 正文总字节数 */
    public final long totalBytes;

    /** 本窗口的解码文本 */
    public final String text;

    public SkillSlice(String skill, int offset, int length, long totalBytes, String text) {
        this.skill = skill;
        this.offset = offset;
        this.length = length;
        this.totalBytes = totalBytes;
        this.text = text;
    }

    /** 是否还有未读完的正文 */
    public boolean truncated() {
        return offset + length < totalBytes;
    }

    @Override
    public String toString() {
        return "SkillSlice(" + skill + ", bytes " + offset + ".." + (offset + length)
                + " of " + totalBytes + ")";
    }

}
