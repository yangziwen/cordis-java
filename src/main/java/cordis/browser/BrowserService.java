package cordis.browser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.WaitUntilState;

import cordis.core.Context;
import cordis.core.Promise;
import cordis.core.Service;
import cordis.http.HttpAccessPolicy;

/**
 * 无头浏览器服务（服务名 {@code browser}）：用 Playwright 驱动真实 Chromium，
 * 把「打开页面 / 读无障碍树 / 点 / 输入 / 截图」做成本层能力。
 *
 * <h3>为什么需要它，{@code http_get} 不够吗</h3>
 *
 * <p>不够。搜索页、后台系统、登录后的列表页，内容都是 JS 跑出来再拼进 DOM 的，
 * HTTP 客户端拿到的只是一副空壳；要「点一下查询按钮再看结果」，更必须有真实的
 * 渲染与交互。本类解决的就是这一类需求。
 *
 * <h3>给模型看什么：无障碍树，不是 HTML</h3>
 *
 * <p>{@link PageSnapshot#snapshot} 来自 {@code ariaSnapshot(mode=AI)}，每个节点
 * 自带 {@code [ref=e12]}，模型引用 ref 就行，不需要自己编 CSS 选择器（选择器会
 * 因为一次改版全部失效）。实测一个带表格与表单的页面，快照约 500 字符，而
 * {@code innerHTML} 是几万字符。
 *
 * <h3>线程模型</h3>
 *
 * <p>Playwright 的调用是阻塞的，和磁盘 IO、JDBC 同性质：公开方法返回
 * {@link Promise}，内部 {@code offload} 到扩展池；{@code *Sync} 变体供已经在线程
 * 池上的调用方（例如 Tool 适配层）使用。在<b>主循环</b>上调 {@code *Sync} 会
 * fail-fast。同一个页面上的并发操作由每页一把 {@link ReentrantLock} 串行化——
 * Playwright 的 Page 不是线程安全的。
 *
 * <h3>成本（务必知道）</h3>
 *
 * <ul>
 *   <li>{@code Playwright.create()} 会拉起 node driver，首次约 9s；</li>
 *   <li>启动 Chromium 约 4s（用系统 Chrome 时实测）；</li>
 *   <li>所以本服务<b>懒启动 + 常驻</b>：第一次 {@code browser_open} 付这笔钱，
 *       之后复用；{@link BrowserPlugin} 会提前预热。</li>
 * </ul>
 */
public class BrowserService extends Service {

    public static final int DEFAULT_MAX_PAGES = 8;

    public static final int DEFAULT_TIMEOUT_MS = 20000;

    public static final int DEFAULT_SNAPSHOT_CHARS = 12000;

    /** 默认 UA：有些站点对 HeadlessChrome 直接返 403，换成常规 Chrome 串更稳 */
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36";

    private final HttpAccessPolicy policy;

    private final Path screenshotDir;

    private final int maxPages;

    private final int timeoutMs;

    private final int snapshotChars;

    private final boolean headless;

    private final String channel;

    private final String userAgent;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final AtomicInteger pageIds = new AtomicInteger();

    private final Object startLock = new Object();

    private Playwright playwright;

    private Browser browser;

    private boolean closed;

    public BrowserService(Context ctx, HttpAccessPolicy policy, Path screenshotDir, int maxPages,
                          int timeoutMs, int snapshotChars, boolean headless, String channel,
                          String userAgent) {
        super(ctx, "browser");
        this.policy = policy == null ? HttpAccessPolicy.denyAll() : policy;
        this.screenshotDir = screenshotDir;
        this.maxPages = Math.max(1, maxPages);
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.snapshotChars = Math.max(1000, snapshotChars);
        this.headless = headless;
        this.channel = channel == null ? "" : channel.trim();
        this.userAgent = userAgent == null || userAgent.trim().isEmpty()
                ? DEFAULT_USER_AGENT : userAgent.trim();
    }

    // ---------- 页面生命周期 ----------

    /** 打开页面并返回首屏快照；{@code waitMs} 是导航后额外等待（SPA 渲染） */
    public Promise<PageSnapshot> open(final String url, final long waitMs) {
        return ctx.scheduler.offload(() -> openSync(url, waitMs));
    }

    public PageSnapshot openSync(String url, long waitMs) {
        assertNotMainLoop("BrowserService.openSync");
        URI target = parse(url);
        check(target);
        evictIfFull();
        long start = System.currentTimeMillis();

        BrowserContext context = browser().newContext(new Browser.NewContextOptions()
                .setUserAgent(userAgent)
                .setLocale("zh-CN")
                .setViewportSize(1440, 900));
        Page page = context.newPage();
        page.setDefaultTimeout(timeoutMs);
        page.setDefaultNavigationTimeout(timeoutMs);

        String id = "p" + pageIds.incrementAndGet();
        try {
            page.navigate(target.toString(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(timeoutMs));
            // 重定向之后的最终地址同样要过策略：白名单站点 302 到内网是最常见的绕过
            check(parse(page.url()));
            if (waitMs > 0) {
                page.waitForTimeout(waitMs);
            }
        } catch (RuntimeException e) {
            closeQuietly(context);
            throw new IllegalStateException("open " + url + " failed: " + summary(e), e);
        }

        Session session = new Session(id, context, page);
        sessions.put(id, session);
        return snapshot(session, start);
    }

    /** 关闭一个页面；返回是否真的关掉了（重复 close 返回 false） */
    public Promise<Boolean> close(final String pageId) {
        return ctx.scheduler.offload(() -> closeSync(pageId));
    }

    public boolean closeSync(String pageId) {
        assertNotMainLoop("BrowserService.closeSync");
        Session session = sessions.remove(pageId);
        if (session == null) {
            return false;
        }
        closeQuietly(session);
        return true;
    }

    public int pageCount() {
        return sessions.size();
    }

    /** 已打开的页面句柄（给 /health 与调试用） */
    public java.util.Set<String> pageIds() {
        return sessions.keySet();
    }

    // ---------- 读 ----------

    public Promise<PageSnapshot> snapshot(final String pageId) {
        return ctx.scheduler.offload(() -> snapshotSync(pageId));
    }

    public PageSnapshot snapshotSync(String pageId) {
        assertNotMainLoop("BrowserService.snapshotSync");
        Session session = session(pageId);
        session.lock.lock();
        try {
            return snapshot(session, System.currentTimeMillis());
        } finally {
            session.lock.unlock();
        }
    }

    // ---------- 交互 ----------

    /** 按 ref 点击，返回点击后的快照（通常就是模型最关心的「结果页」） */
    public Promise<PageSnapshot> click(final String pageId, final String ref) {
        return ctx.scheduler.offload(() -> clickSync(pageId, ref));
    }

    public PageSnapshot clickSync(String pageId, String ref) {
        assertNotMainLoop("BrowserService.clickSync");
        Session session = session(pageId);
        session.lock.lock();
        try {
            long start = System.currentTimeMillis();
            session.page.getByRef(requireRef(ref)).click();
            return snapshot(session, start);
        } catch (RuntimeException e) {
            throw new IllegalStateException("click " + ref + " failed: " + summary(e), e);
        } finally {
            session.lock.unlock();
        }
    }

    /** 按 ref 填入文本；{@code submit} 为真时回车提交 */
    public Promise<PageSnapshot> type(final String pageId, final String ref,
                                     final String text, final boolean submit) {
        return ctx.scheduler.offload(() -> typeSync(pageId, ref, text, submit));
    }

    public PageSnapshot typeSync(String pageId, String ref, String text, boolean submit) {
        assertNotMainLoop("BrowserService.typeSync");
        Session session = session(pageId);
        session.lock.lock();
        try {
            long start = System.currentTimeMillis();
            session.page.getByRef(requireRef(ref)).fill(text == null ? "" : text);
            if (submit) {
                session.page.getByRef(ref).press("Enter");
            }
            return snapshot(session, start);
        } catch (RuntimeException e) {
            throw new IllegalStateException("type into " + ref + " failed: " + summary(e), e);
        } finally {
            session.lock.unlock();
        }
    }

    /** 发送一个按键（Enter / Tab / Escape / ArrowDown …） */
    public Promise<PageSnapshot> press(final String pageId, final String key) {
        return ctx.scheduler.offload(() -> pressSync(pageId, key));
    }

    public PageSnapshot pressSync(String pageId, String key) {
        assertNotMainLoop("BrowserService.pressSync");
        Session session = session(pageId);
        session.lock.lock();
        try {
            long start = System.currentTimeMillis();
            session.page.keyboard().press(key);
            return snapshot(session, start);
        } catch (RuntimeException e) {
            throw new IllegalStateException("press " + key + " failed: " + summary(e), e);
        } finally {
            session.lock.unlock();
        }
    }

    /** 干等一会再看快照：懒加载页面没有可等的元素时用它 */
    public Promise<PageSnapshot> waitFor(final String pageId, final long ms) {
        return ctx.scheduler.offload(() -> waitSync(pageId, ms));
    }

    public PageSnapshot waitSync(String pageId, long ms) {
        assertNotMainLoop("BrowserService.waitSync");
        Session session = session(pageId);
        session.lock.lock();
        try {
            long start = System.currentTimeMillis();
            session.page.waitForTimeout(Math.max(0, Math.min(ms, 60000)));
            return snapshot(session, start);
        } finally {
            session.lock.unlock();
        }
    }

    // ---------- 截图 ----------

    /** 整页截图，返回落盘后的绝对路径（模型看不到图，但人可以在工作区里看） */
    public Promise<String> screenshot(final String pageId) {
        return ctx.scheduler.offload(() -> screenshotSync(pageId));
    }

    public String screenshotSync(String pageId) {
        assertNotMainLoop("BrowserService.screenshotSync");
        Session session = session(pageId);
        session.lock.lock();
        try {
            Files.createDirectories(screenshotDir);
            Path file = screenshotDir.resolve("shot-" + pageId + "-"
                    + System.currentTimeMillis() + ".png");
            session.page.screenshot(new Page.ScreenshotOptions().setPath(file));
            return file.toString();
        } catch (IOException e) {
            throw new IllegalStateException("screenshot dir not writable: " + screenshotDir, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("screenshot failed: " + summary(e), e);
        } finally {
            session.lock.unlock();
        }
    }

    // ---------- 预热与关闭 ----------

    /** 提前把 driver + 浏览器拉起来，避免首个工具调用等 13s */
    public void prewarmSync() {
        assertNotMainLoop("BrowserService.prewarmSync");
        if (isClosed()) {
            return;
        }
        browser();
    }

    /** 已卸载：预热与工具调用据此早退，不把 driver 拉到一半再被打断 */
    public boolean isClosed() {
        synchronized (startLock) {
            return closed;
        }
    }

    public boolean isStarted() {
        synchronized (startLock) {
            return browser != null && browser.isConnected();
        }
    }

    /** 关掉所有页面与浏览器（插件卸载时调用），可重复调用 */
    public void shutdownSync() {
        assertNotMainLoop("BrowserService.shutdownSync");
        for (Session session : sessions.values()) {
            closeQuietly(session);
        }
        sessions.clear();
        synchronized (startLock) {
            closed = true;
            if (browser != null) {
                try {
                    browser.close();
                } catch (RuntimeException ignored) {
                    // 进程已经没了也无所谓，下面还会关 driver
                }
                browser = null;
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (RuntimeException ignored) {
                    // 同上
                }
                playwright = null;
            }
        }
    }

    // ---------- 内部 ----------

    private Browser browser() {
        synchronized (startLock) {
            if (closed) {
                throw new IllegalStateException("browser service is closed");
            }
            if (browser != null && browser.isConnected()) {
                return browser;
            }
            long begin = System.currentTimeMillis();
            if (playwright == null) {
                // 首次 9s（解压 driver bundle），之后走 OS 文件缓存约 0.4s
                System.out.println("[browser] starting driver (node subprocess)");
                Playwright.CreateOptions createOptions = createOptions();
                playwright = createOptions == null
                        ? Playwright.create()
                        : Playwright.create(createOptions);
            }
            long driverReady = System.currentTimeMillis();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
            if (!channel.isEmpty()) {
                options.setChannel(channel);
            }
            browser = playwright.chromium().launch(options);
            long launched = System.currentTimeMillis();
            // 启动是低频事件（一次进程一次），把 9s+4s 拆开打出来，卡住时能定位
            System.out.println("[browser] driver ready in " + (driverReady - begin)
                    + "ms, browser up in " + (launched - driverReady) + "ms ("
                    + (channel.isEmpty() ? "bundled" : channel) + ", headless=" + headless + ")");
            return browser;
        }
    }

    /**
     * 用系统浏览器时（channel 非空）告诉 driver「不要自动安装浏览器」。
     *
     * <p>很多机器上只有系统 Chrome，没有 Playwright 的 chromium / firefox / webkit；
     * 一旦允许自动安装，{@code create()} 会去下载几百 MB（内网无外网时直接失败，
     * 整个浏览器能力用不了）。这里用 {@code CreateOptions.setEnv} 注入，而不是要求
     * 调用方 {@code export} 环境变量——配置只留一个口子。实测 setEnv 是<b>合并</b>
     * 到当前环境（PATH 等保留），且 {@code channel=chrome} 在空 browsers 目录下照样
     * 能起 Chrome 158。
     *
     * <p>用自带二进制时（channel 空）不注入：那种场景本来就需要自带浏览器。
     */
    private Playwright.CreateOptions createOptions() {
        if (channel.isEmpty()) {
            return null;
        }
        Map<String, String> env = new HashMap<String, String>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return new Playwright.CreateOptions().setEnv(env);
    }

    private Session session(String pageId) {
        Session session = sessions.get(pageId);
        if (session == null) {
            throw new IllegalArgumentException("unknown page \"" + pageId
                    + "\" (it may have been closed; open it again)");
        }
        return session;
    }

    private PageSnapshot snapshot(Session session, long start) {
        session.lastUsed = System.currentTimeMillis();
        Page page = session.page;
        String aria;
        try {
            aria = page.ariaSnapshot(new Page.AriaSnapshotOptions()
                    .setMode(AriaSnapshotMode.AI));
        } catch (RuntimeException e) {
            aria = "";
        }
        Cut cut = cut(aria);
        String text = "";
        if (cut.text.trim().isEmpty()) {
            try {
                text = page.innerText("body");
            } catch (RuntimeException e) {
                text = "";
            }
        }
        Cut textCut = cut(text);
        return new PageSnapshot(session.id, page.url(), title(page), cut.text, textCut.text,
                cut.truncated || textCut.truncated, System.currentTimeMillis() - start);
    }

    private static String title(Page page) {
        try {
            return page.title();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 超长快照头尾都留：正文常在底部，只留头部会把答案切掉 */
    private Cut cut(String text) {
        if (text == null) {
            return new Cut("", false);
        }
        if (text.length() <= snapshotChars) {
            return new Cut(text, false);
        }
        int head = (int) (snapshotChars * 0.7);
        int tail = snapshotChars - head;
        String cut = text.substring(0, head)
                + "\n…(omitted " + (text.length() - snapshotChars) + " chars)…\n"
                + text.substring(text.length() - tail);
        return new Cut(cut, true);
    }

    private void evictIfFull() {
        while (sessions.size() >= maxPages) {
            Session oldest = null;
            for (Session session : sessions.values()) {
                if (oldest == null || session.lastUsed < oldest.lastUsed) {
                    oldest = session;
                }
            }
            if (oldest == null) {
                return;
            }
            sessions.remove(oldest.id);
            closeQuietly(oldest);
        }
    }

    private static void closeQuietly(Session session) {
        session.lock.lock();
        try {
            session.context.close();
        } catch (RuntimeException ignored) {
            // 浏览器可能已经被关掉，这里不需要再抛
        } finally {
            session.lock.unlock();
        }
    }

    private static void closeQuietly(BrowserContext context) {
        try {
            context.close();
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    private void check(URI target) {
        if (!policy.allow(target)) {
            throw new SecurityException("browser navigation denied: " + target
                    + " (host is not in the allowed list)");
        }
    }

    private static String requireRef(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("missing \"ref\" (take it from the snapshot,"
                    + " e.g. \"e12\")");
        }
        return ref.trim();
    }

    /** 只接受 http/https：{@code file://} 能直接读本机文件，{@code data:} 能绕开白名单 */
    private static URI parse(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("missing \"url\"");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid url: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new SecurityException("browser only opens http/https, got: " + url);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("url has no host: " + url);
        }
        return uri;
    }

    /** Playwright 的异常消息带着整段 JS 栈，喂给模型前必须压成一行 */
    private static String summary(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        int callLog = message.indexOf("Call log:");
        if (callLog > 0) {
            message = message.substring(0, callLog);
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }

    private void assertNotMainLoop(String operation) {
        if (Thread.currentThread() == ctx.scheduler.mainLoopThread()) {
            throw new IllegalStateException(operation + " drives a headless browser and must"
                    + " not run on the main loop; use the Promise-returning variant instead");
        }
    }

    @Override
    public String toString() {
        return "BrowserService(pages=" + sessions.size() + ", started=" + isStarted()
                + ", headless=" + headless + ")";
    }

    /** 一个页面 = 一个独立 BrowserContext（cookie / 存储天然隔离） */
    private static final class Session {

        private final String id;

        private final BrowserContext context;

        private final Page page;

        private final ReentrantLock lock = new ReentrantLock();

        private volatile long lastUsed = System.currentTimeMillis();

        private Session(String id, BrowserContext context, Page page) {
            this.id = id;
            this.context = context;
            this.page = page;
        }

    }

    private static final class Cut {

        private final String text;

        private final boolean truncated;

        private Cut(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }

    }

}
