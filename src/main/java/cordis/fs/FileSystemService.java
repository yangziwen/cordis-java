package cordis.fs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import cordis.core.Context;
import cordis.core.Promise;
import cordis.core.Service;

/**
 * 文件系统服务（服务名 {@code filesystem}）：把「读写沙箱内文件」做成一个
 * 和 {@code cordis.sqlite}、{@code cordis.http} 平级的能力。
 *
 * <h3>它是能力层，不是 agent 的一部分</h3>
 *
 * <p>本类不认识 agent、不返回给模型看的 JSON、不依赖任何 JSON 库：接口用
 * {@link FileEntry} / {@link FileSlice} / {@link WriteResult} / {@link GrepHit}
 * 这些普通值对象。给模型看的 schema、参数解析、结果塑形发生在适配层
 * （{@code cordis.agent.fs}）——和 {@code cordis.http}（能力）与
 * {@code cordis.agent.llm}（OpenAI 协议适配）的分工完全一致。
 *
 * <h3>安全模型</h3>
 *
 * <ul>
 *   <li><b>沙箱</b>：root 之外的一切路径（绝对路径、{@code ../}、符号链接
 *       逃逸）在 {@link PathScope} 里被拒绝；</li>
 *   <li><b>大小上限</b>：读有 maxReadBytes（也是 grep 单文件的扫描上限），
 *       写有 maxWriteBytes——先量再动，不是读完再截断；</li>
 *   <li><b>只碰文本</b>：二进制文件（前 8KB 含 NUL）直接拒绝，避免把字节流
 *       当 UTF-8 解码出乱码再写回去；</li>
 *   <li><b>写前审批</b>：write / edit 动手之前先过 {@link ApprovalGate}，
 *       默认拒绝一切；</li>
 *   <li><b>原子写</b>：先写同目录临时文件，再 {@code ATOMIC_MOVE} 换过去
 *       （文件系统不支持原子换时退化为普通 rename）。写到一半被杀，磁盘上
 *       留下的只会是一个没人引用的临时文件，而不是半个目标文件。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 *
 * <p>磁盘 IO 是阻塞的，和 JDBC 同一性质。公开方法返回 {@link Promise}，
 * 内部 {@code offload} 到扩展池；{@code *Sync} 变体供已经在线程池上的调用方
 * 复用（例如 agent 的 Tool 适配层——{@code Tool.run} 本来就跑在 worker /
 * toolPool 上）。在<b>主循环</b>上调 {@code *Sync} 会 fail-fast。
 */
public class FileSystemService extends Service {

    /** 单次读取的行数上限：护的是模型上下文，不是内存 */
    private static final int MAX_READ_LINES = 2000;

    /** 数总行数的上限：超过就放弃精确计数，{@code totalLines} 报 -1 */
    private static final int MAX_COUNT_LINES = 500_000;

    /** 单目录条目上限：目录本身大到失控时拒绝而不是默默吐一半 */
    private static final int MAX_DIR_ENTRIES = 1000;

    /** grep 最多扫描的文件数 */
    private static final int MAX_GREP_FILES = 2000;

    /** grep 最多返回的命中数 */
    private static final int MAX_GREP_HITS = 200;

    /** grep 命中行文本的展示上限 */
    private static final int MAX_HIT_TEXT = 200;

    /** 审批预览的长度上限 */
    private static final int MAX_PREVIEW = 200;

    private final PathScope scope;

    private final long maxReadBytes;

    private final long maxWriteBytes;

    private final ApprovalGate approval;

    /**
     * @param realRoot 已归一化的真实路径（{@code toRealPath()} 之后），由
     *                 {@link FileSystemPlugin} 在扩展池上算好传入——Service
     *                 构造必须发生在主循环，那里不能碰磁盘
     */
    public FileSystemService(Context ctx, Path realRoot, long maxReadBytes,
                             long maxWriteBytes, ApprovalGate approval) {
        super(ctx, "filesystem");
        this.scope = new PathScope(realRoot);
        this.maxReadBytes = maxReadBytes;
        this.maxWriteBytes = maxWriteBytes;
        this.approval = approval == null ? ApprovalGate.denyAll() : approval;
    }

    // ---------- 读 ----------

    /** 列出目录内容（不递归），按名字排序 */
    public Promise<List<FileEntry>> list(final String path) {
        return ctx.scheduler.offload(() -> listSync(path));
    }

    public List<FileEntry> listSync(String path) {
        assertNotMainLoop("FileSystemService.listSync");
        Path dir = scope.resolve(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + path);
        }
        List<FileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                boolean directory = Files.isDirectory(entry);
                entries.add(new FileEntry(entry.getFileName().toString(),
                        directory, directory ? -1L : sizeOf(entry)));
                if (entries.size() > MAX_DIR_ENTRIES) {
                    throw new IllegalStateException("directory has more than "
                            + MAX_DIR_ENTRIES + " entries: " + dir.getFileName()
                            + "; narrow it down before listing");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("list failed: " + dir, e);
        }
        entries.sort(Comparator.comparing(entry -> entry.name));
        return entries;
    }

    /**
     * 读取文本文件的一段：从 {@code startLine}（1 基）开始最多 {@code maxLines} 行。
     * 非法入参按 1 / 30 处理。
     */
    public Promise<FileSlice> read(final String path, final int startLine, final int maxLines) {
        return ctx.scheduler.offload(() -> readSync(path, startLine, maxLines));
    }

    public FileSlice readSync(String path, int startLine, int maxLines) {
        assertNotMainLoop("FileSystemService.readSync");
        Path file = scope.resolve(path);
        requireRegularFile(file);
        requireWithinReadCap(file);
        requireText(file);
        if (startLine < 1) {
            startLine = 1;
        }
        if (maxLines < 1) {
            maxLines = 30;
        }
        int limit = Math.min(maxLines, MAX_READ_LINES);
        List<String> lines = new ArrayList<>(limit);
        int total = 0;
        boolean countingCapped = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                if (total >= startLine && lines.size() < limit) {
                    lines.add(line);
                }
                if (total > MAX_COUNT_LINES) {
                    countingCapped = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("read failed: " + file, e);
        }
        return new FileSlice(file.toString(), startLine, lines,
                countingCapped ? -1 : total);
    }

    /**
     * 在整个工作区里按正则找文本（跳过 .git / target / node_modules 与隐藏目录）。
     *
     * @param pattern java 正则（{@code find} 语义，不是整行匹配）
     * @param glob    文件名过滤，如 {@code *.java}；null / 空表示不过滤
     */
    public Promise<List<GrepHit>> grep(final String pattern, final String glob) {
        return ctx.scheduler.offload(() -> grepSync(pattern, glob));
    }

    public List<GrepHit> grepSync(String pattern, String glob) {
        assertNotMainLoop("FileSystemService.grepSync");
        if (pattern == null || pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        final Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + pattern, e);
        }
        final PathMatcher matcher = glob == null || glob.trim().isEmpty()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
        final List<GrepHit> hits = new ArrayList<>();
        final AtomicLong scanned = new AtomicLong();
        try {
            Files.walkFileTree(scope.root(), new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(scope.root()) && isIgnoredDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (hits.size() >= MAX_GREP_HITS || scanned.get() >= MAX_GREP_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    scanned.incrementAndGet();
                    if (matcher == null || matcher.matches(file.getFileName())) {
                        scanFile(file, regex, hits);
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            throw new IllegalStateException("grep failed under " + scope.root(), e);
        }
        return hits;
    }

    // ---------- 写 ----------

    /** 新建或整体覆盖一个文本文件（先过审批关口，再原子落盘） */
    public Promise<WriteResult> write(final String path, final String content) {
        return ctx.scheduler.offload(() -> writeSync(path, content));
    }

    public WriteResult writeSync(String path, String content) {
        assertNotMainLoop("FileSystemService.writeSync");
        Path target = scope.resolve(path);
        String text = content == null ? "" : content;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxWriteBytes) {
            throw new IllegalStateException("content too large: " + bytes.length
                    + " bytes > cap " + maxWriteBytes);
        }
        boolean created = !Files.exists(target);
        if (!created) {
            requireRegularFile(target);
            requireText(target);
        }
        approve(new WriteRequest("write", target, bytes.length, clip(text)));
        atomicWrite(target, bytes);
        return new WriteResult("write", target.toString(), created, bytes.length);
    }

    /**
     * 精确替换：{@code oldText} 必须在文件里恰好出现一次，出现 0 次或多次都拒绝
     * ——「猜一个大概的位置」是模型改错文件的头号来源，宁可不改。
     */
    public Promise<WriteResult> edit(final String path, final String oldText,
                                     final String newText) {
        return ctx.scheduler.offload(() -> editSync(path, oldText, newText));
    }

    public WriteResult editSync(String path, String oldText, String newText) {
        assertNotMainLoop("FileSystemService.editSync");
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty");
        }
        Path target = scope.resolve(path);
        requireRegularFile(target);
        requireWithinReadCap(target);
        requireText(target);
        String content = readAll(target);
        int occurrences = countOccurrences(content, oldText);
        if (occurrences == 0) {
            throw new IllegalStateException("oldText not found in " + target.getFileName()
                    + "; read the file first and copy the exact text");
        }
        if (occurrences > 1) {
            throw new IllegalStateException("oldText matches " + occurrences
                    + " places in " + target.getFileName()
                    + "; make it longer so it matches exactly one place");
        }
        String updated = content.replace(oldText, newText == null ? "" : newText);
        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxWriteBytes) {
            throw new IllegalStateException("content too large after edit: " + bytes.length
                    + " bytes > cap " + maxWriteBytes);
        }
        approve(new WriteRequest("edit", target, bytes.length,
                "old: " + clip(oldText) + " -> new: " + clip(newText)));
        atomicWrite(target, bytes);
        return new WriteResult("edit", target.toString(), false, bytes.length);
    }

    // ---------- 内部：校验 ----------

    private static void requireRegularFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("not a regular file: " + file);
        }
    }

    private void requireWithinReadCap(Path file) {
        long size = sizeOf(file);
        if (size > maxReadBytes) {
            throw new IllegalStateException("file too large: " + size
                    + " bytes > cap " + maxReadBytes + ": " + file.getFileName());
        }
    }

    /** 前 8KB 含 NUL 字节即视为二进制；读不动的文件也按二进制拒绝 */
    private static void requireText(Path file) {
        if (isBinary(file)) {
            throw new IllegalStateException("binary file not supported: "
                    + file.getFileName() + " (text only)");
        }
    }

    private static boolean isBinary(Path file) {
        byte[] head = new byte[8192];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(head);
        } catch (IOException e) {
            return true;
        }
        for (int i = 0; i < Math.max(read, 0); i++) {
            if (head[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private void approve(WriteRequest request) {
        boolean approved;
        try {
            approved = approval.approve(request);
        } catch (RuntimeException e) {
            throw new IllegalStateException("approval gate failed for " + request.path, e);
        }
        if (!approved) {
            throw new IllegalStateException("write denied by approval gate: " + request.path);
        }
    }

    // ---------- 内部：IO ----------

    private static void atomicWrite(Path target, byte[] bytes) {
        Path tmp = target.resolveSibling("." + target.getFileName()
                + ".tmp-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupported) {
                // 同目录 rename 天然原子；ATOMIC_MOVE 只是更强的保证，不支持也能退
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new IllegalStateException("write failed: " + target, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("[fs] cleanup of temp file failed: " + file);
        }
    }

    private static String readAll(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("read failed: " + file, e);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot stat " + file, e);
        }
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        for (int i = content.indexOf(needle); i >= 0;
                i = content.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private void scanFile(Path file, Pattern regex, List<GrepHit> hits) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        if (sizeOfQuietly(file) > maxReadBytes) {
            return;
        }
        if (isBinary(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (regex.matcher(line).find()) {
                    hits.add(new GrepHit(file.toString(), lineNumber,
                            clip(line.trim(), MAX_HIT_TEXT)));
                    if (hits.size() >= MAX_GREP_HITS) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            // 单个文件解码失败 / 读不动：跳过它，grep 整体不受影响
        }
    }

    private static long sizeOfQuietly(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isIgnoredDirectory(Path dir) {
        String name = dir.getFileName().toString();
        return name.startsWith(".") || "target".equals(name) || "node_modules".equals(name);
    }

    private static String clip(String text) {
        return clip(text, MAX_PREVIEW);
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() <= limit) {
            return flat;
        }
        return flat.substring(0, limit) + "…";
    }

    private void assertNotMainLoop(String operation) {
        if (Thread.currentThread() == ctx.scheduler.mainLoopThread()) {
            throw new IllegalStateException(operation + " performs blocking file IO and must"
                    + " not run on the main loop; use the Promise-returning variant instead");
        }
    }

    @Override
    public String toString() {
        return "FileSystemService(root=" + scope.root() + ")";
    }

}
