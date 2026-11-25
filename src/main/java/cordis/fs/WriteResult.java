package cordis.fs;

/**
 * 一次成功写入的结果（{@link FileSystemService#writeSync} /
 * {@link FileSystemService#editSync}）。
 *
 * <p>只报「落盘之后的事实」；「改了什么」的摘要给模型的职责在适配层
 * （agent.fs 的 Tool 自己拼 old/new 展示），能力层不掺和展示语义。
 */
public final class WriteResult {

    /** {@code write}（整体覆盖）或 {@code edit}（局部替换） */
    public final String action;

    /** 目标文件（沙箱内的绝对真实路径） */
    public final String path;

    /** 本次是否新建了文件（false = 覆盖 / 修改已存在的文件） */
    public final boolean created;

    /** 写入后的字节大小（UTF-8） */
    public final long bytes;

    public WriteResult(String action, String path, boolean created, long bytes) {
        this.action = action;
        this.path = path;
        this.created = created;
        this.bytes = bytes;
    }

    @Override
    public String toString() {
        return "WriteResult(" + action + ", " + path + ", "
                + (created ? "created" : "overwritten") + ", " + bytes + " bytes)";
    }

}
