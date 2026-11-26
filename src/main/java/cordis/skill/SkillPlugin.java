package cordis.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cordis.core.Component;
import cordis.core.Context;
import cordis.core.Disposable;
import cordis.core.Promise;

/**
 * 技能插件（异步初始化版，与 {@code FileSystemPlugin} 同款两段式）：
 * 阻塞的目录扫描经 {@code offload} 投到扩展池，扫描完回主循环构造
 * {@link SkillService}。
 *
 * <h3>目录约定</h3>
 *
 * <pre>
 * root/
 *   markdown-writer/
 *     skill.json       ← 元数据：{"description": "..."}；可缺省
 *     SKILL.md         ← 正文，按需加载（目录名 = 技能名）
 *     references/      ← 二级资源，读它不是本插件的事
 *   csv-reporter/
 *     skill.json
 *     SKILL.md
 * </pre>
 *
 * <p>只认一级子目录里的 {@code SKILL.md}（那是正文，必需）；没有该文件的
 * 目录直接跳过（references、node_modules 之类的陪跑目录很常见）。
 * {@code skill.json} 是可选的元数据，缺失或写坏时退化到正文首个非空行做
 * description 并打警告——一个坏元数据不该拖垮整个技能目录。详见
 * {@link SkillMeta}。
 *
 * <h3>配置</h3>
 *
 * <ul>
 *   <li>{@code root}（必填）：技能根目录；</li>
 *   <li>{@code createRoot}（默认 false）：不存在时是否创建（技能目录缺失
 *       多半是配置错误，默认以空清单 + 警告收场而不是静默建空目录）；</li>
 *   <li>{@code maxDescriptionChars}（默认 200）：清单里每条描述的截断；</li>
 *   <li>{@code maxLoadBytes}（默认 8192）：正文窗口大小；</li>
 *   <li>{@code maxSkills}（默认 100）：目录里技能过多时截断，防止清单
 *       本身吃满 system 段。</li>
 * </ul>
 *
 * <p>清理函数无事可做：服务不持句柄，重装插件即重扫——这正是技能增删
 * 生效的方式，复用 fiber reload。
 */
public class SkillPlugin implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(SkillPlugin.class);

    /** 默认清单描述截断：200 字符 */
    public static final int DEFAULT_MAX_DESCRIPTION_CHARS = 200;

    /** 默认正文窗口：8KB（中位真实 skill 约 14.5KB，一轮可读完大半） */
    public static final int DEFAULT_MAX_LOAD_BYTES = 8192;

    /** 默认技能数上限 */
    public static final int DEFAULT_MAX_SKILLS = 100;

    /** 解析元数据缺失时退化用：读正文头部的字节数 */
    private static final int HEADER_PREVIEW_BYTES = 8192;

    public static final SkillPlugin INSTANCE = new SkillPlugin();

    private SkillPlugin() {
    }

    @Override
    public String getName() {
        return "skills";
    }

    @Override
    public Disposable apply(Context ctx, Map<String, Object> config) {
        throw new UnsupportedOperationException("本插件只走 applyAsync（异步扫描目录）");
    }

    @Override
    public Promise<Disposable> applyAsync(final Context ctx, Map<String, Object> config) {
        Object rootConfig = config.get("root");
        if (rootConfig == null || String.valueOf(rootConfig).trim().isEmpty()) {
            return Promise.rejected(ctx.scheduler,
                    new IllegalArgumentException("skill plugin requires config \"root\""
                            + " (the directory holding one <skill>/SKILL.md per entry)"));
        }
        final int maxDescriptionChars = intConfig(config, "maxDescriptionChars",
                DEFAULT_MAX_DESCRIPTION_CHARS);
        final int maxLoadBytes = intConfig(config, "maxLoadBytes", DEFAULT_MAX_LOAD_BYTES);
        final int maxSkills = intConfig(config, "maxSkills", DEFAULT_MAX_SKILLS);
        final boolean createRoot = booleanConfig(config, "createRoot", false);
        final Path root = Paths.get(String.valueOf(rootConfig).trim());

        return ctx.scheduler.<List<Skill>>offload(() -> {
                    List<Skill> skills = scan(root, createRoot, maxSkills);
                    LOG.info("{} skill(s) ready from {}", skills.size(), root);
                    return skills;
                })
                .then(skills -> new SkillService(ctx, skills, maxDescriptionChars, maxLoadBytes))
                .then(service -> (Disposable) () -> {
                    // 无句柄可关：快照型服务，重装即重扫
                });
    }

    // ---------- 扫描（worker 线程） ----------

    private static List<Skill> scan(Path root, boolean createRoot, int maxSkills)
            throws IOException {
        if (!Files.exists(root)) {
            if (createRoot) {
                Files.createDirectories(root);
            } else {
                // 空目录是合法配置（比如还没放技能），只说一声，不算异常
                LOG.info("skill root does not exist (empty catalog): {}", root);
                return new ArrayList<Skill>();
            }
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("skill root is not a directory: " + root);
        }
        List<Path> dirs = new ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    dirs.add(entry);
                }
            }
        }
        dirs.sort((a, b) -> a.getFileName().toString()
                .compareTo(b.getFileName().toString()));
        List<Skill> skills = new ArrayList<Skill>();
        for (Path dir : dirs) {
            Path file = dir.resolve("SKILL.md");
            if (!Files.isRegularFile(file)) {
                continue;                                    // 陪跑目录（references 等）
            }
            if (skills.size() >= maxSkills) {
                LOG.warn("skill count exceeds maxSkills={}, ignoring the rest", maxSkills);
                break;
            }
            skills.add(load(dir.getFileName().toString(), dir));
        }
        return skills;
    }

    /** 元数据取 skill.json；缺失/坏掉时退化到正文首个非空行 */
    private static Skill load(String name, Path dir) throws IOException {
        Path file = dir.resolve("SKILL.md");
        long totalBytes = Files.size(file);
        String description = SkillMeta.description(dir);
        if (description == null || description.trim().isEmpty()) {
            description = firstContentLine(readHead(file, totalBytes));
        }
        return new Skill(name, description, file.toRealPath(), totalBytes);
    }

    /** 读文件头（最多 {@link #HEADER_PREVIEW_BYTES} 字节）用于退化描述 */
    private static String readHead(Path file, long totalBytes) throws IOException {
        byte[] head = new byte[(int) Math.min(HEADER_PREVIEW_BYTES, totalBytes)];
        int read = 0;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return new String(head, 0, read, StandardCharsets.UTF_8);
    }

    /** 正文里第一个非空行（跳过可能残留的 --- 分隔行） */
    private static String firstContentLine(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !"---".equals(trimmed)) {
                return trimmed;
            }
        }
        return "";
    }

    // ---------- config 解析 ----------

    private static int intConfig(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("config \"" + key + "\" is not a number: "
                        + value);
            }
        }
        return fallback;
    }

    private static boolean booleanConfig(Map<String, Object> config, String key,
                                         boolean fallback) {
        Object value = config.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

}
