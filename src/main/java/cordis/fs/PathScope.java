package cordis.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区路径沙箱：所有 {@link FileSystemService} 收到的路径都必须先经过这里。
 *
 * <h3>两道校验</h3>
 *
 * <ol>
 *   <li><b>词法校验</b>：{@code root.resolve(raw).normalize()} 之后必须仍在
 *       root 内。{@code Path.resolve} 对绝对路径参数会整个替换掉 root，所以
 *       「传一个 /etc/passwd 进来」在这一步就会被打回；{@code ../} 翻出墙外
 *       同理。normalize 之后做 startsWith，才不会被 {@code a/../../b} 骗过。</li>
 *   <li><b>真实路径校验</b>：对已存在的部分（文件本身或它的父目录）做
 *       {@code toRealPath()} 再比一次 root。挡的是<b>符号链接</b>：root 里放
 *       一个指向墙外的链接，词法上完全合法，只有真实路径会露馅。</li>
 * </ol>
 *
 * <h3>root 必须是真实路径</h3>
 *
 * 本类不再对 root 做 {@code toRealPath()}——那是阻塞磁盘操作，由
 * {@link FileSystemPlugin} 在扩展池上完成后再把<b>已经归一化的真实路径</b>
 * 传进来（Service 构造必须发生在主循环，不能在那里碰磁盘）。这也是
 * macOS 上 {@code /tmp → /private/tmp} 这类系统级软链不会被误判的原因：
 * root 从一开始就是真实形态，词法比对和真实比对落在同一个基准上。
 *
 * <p>包级私有：它是 {@link FileSystemService} 的实现细节，不是能力层 API。
 */
final class PathScope {

    private final Path root;

    PathScope(Path realRoot) {
        if (realRoot == null) {
            throw new IllegalArgumentException("workspace root must not be null");
        }
        this.root = realRoot;
    }

    /** 工作区根（真实路径） */
    Path root() {
        return root;
    }

    /**
     * 把调用方给的路径文本解析成沙箱内的 {@link Path}。
     *
     * @throws IllegalArgumentException 在沙箱外、是空串、或经符号链接逃逸
     */
    Path resolve(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        Path candidate = root.resolve(raw.trim()).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace root " + root
                    + ": " + raw);
        }
        verifyReal(candidate, raw);
        return candidate;
    }

    /** 已存在的部分按真实路径再比一次 root，挡住符号链接逃逸 */
    private void verifyReal(Path candidate, String raw) {
        Path anchor = Files.exists(candidate) ? candidate : candidate.getParent();
        if (anchor == null) {
            return;
        }
        try {
            if (!anchor.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("symlink escapes workspace root " + root
                        + ": " + raw);
            }
        } catch (IOException e) {
            // toRealPath 失败（权限等）：宁可拒绝也不放过
            throw new IllegalArgumentException("cannot verify real path of " + raw, e);
        }
    }

}
