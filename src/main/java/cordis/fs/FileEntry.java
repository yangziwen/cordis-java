package cordis.fs;

/**
 * 目录里的一项（{@link FileSystemService#listSync} 的元素）。
 */
public final class FileEntry {

    /** 文件 / 目录名（不含路径） */
    public final String name;

    /** 是否目录 */
    public final boolean directory;

    /** 文件字节大小；目录恒为 -1 */
    public final long sizeBytes;

    public FileEntry(String name, boolean directory, long sizeBytes) {
        this.name = name;
        this.directory = directory;
        this.sizeBytes = sizeBytes;
    }

    @Override
    public String toString() {
        return name + (directory ? "/" : " (" + sizeBytes + "B)");
    }

}
