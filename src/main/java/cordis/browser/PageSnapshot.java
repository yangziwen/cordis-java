package cordis.browser;

/**
 * 一次「页面快照」的结果：给模型看的全部信息都在这里。
 *
 * <p>{@link #snapshot} 是 Playwright 的无障碍树快照（AI 模式），每个可交互
 * 节点自带 {@code [ref=e12]}；模型下一步说「点 e12」，适配层就
 * {@code page.getByRef("e12").click()}。用无障碍树而不是 innerHTML 的原因：
 * 一个电商页的 DOM 有几十万字符，无障碍树通常只有几千，且天然滤掉导航栏、
 * 广告、内联样式这些噪声。
 *
 * <p>{@link #text} 是 {@code body} 的可见文本，仅在无障碍树为空时回填（少数
 * canvas / 纯文本页），避免同一份内容喂两遍浪费上下文。
 */
public final class PageSnapshot {

    /** 句柄：后续 click / type / close 都带它 */
    public final String pageId;

    /** 导航后的最终地址（跟随重定向之后） */
    public final String url;

    public final String title;

    /** 无障碍树（AI 模式，带 ref），可能被截断 */
    public final String snapshot;

    /** 可见文本兜底，可能为空串 */
    public final String text;

    /** snapshot 是否被截断 */
    public final boolean truncated;

    /** 本次操作耗时（毫秒） */
    public final long elapsedMs;

    public PageSnapshot(String pageId, String url, String title, String snapshot,
                        String text, boolean truncated, long elapsedMs) {
        this.pageId = pageId;
        this.url = url;
        this.title = title;
        this.snapshot = snapshot;
        this.text = text;
        this.truncated = truncated;
        this.elapsedMs = elapsedMs;
    }

    @Override
    public String toString() {
        return "PageSnapshot(" + pageId + ", " + title + ", " + url
                + ", snapshot=" + snapshot.length() + " chars)";
    }

}
