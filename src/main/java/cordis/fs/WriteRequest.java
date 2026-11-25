package cordis.fs;

import java.nio.file.Path;

/**
 * 一次待审批的写入：能力层在动手<b>之前</b>把要动的东西摊开给关口看。
 *
 * <p>preview 是人能读的改动摘要（write 给新内容开头，edit 给 old → new
 * 的节选），UI 弹窗 / stdin 确认直接展示它即可，不必再读一次文件。
 */
public final class WriteRequest {

    /** 动作类型：{@code write}（整体覆盖）或 {@code edit}（局部替换） */
    public final String action;

    /** 目标文件（沙箱内的绝对真实路径） */
    public final String path;

    /** 写入后的字节大小（UTF-8） */
    public final long bytes;

    /** 改动摘要（旧 / 新内容节选，已截断） */
    public final String preview;

    public WriteRequest(String action, Path path, long bytes, String preview) {
        this.action = action;
        this.path = path.toString();
        this.bytes = bytes;
        this.preview = preview;
    }

    @Override
    public String toString() {
        return "WriteRequest(" + action + ", " + path + ", " + bytes + " bytes)";
    }

}
