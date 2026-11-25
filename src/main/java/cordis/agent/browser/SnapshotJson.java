package cordis.agent.browser;

import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.browser.PageSnapshot;

/**
 * 把能力层的 {@link PageSnapshot} 塑形成模型看的 JSON。
 *
 * <p>字段取舍：{@code snapshot} 是无障碍树（带 ref），模型主要靠它决策；
 * {@code text} 只在与 snapshot 互补时给出；{@code truncated} 提醒模型「后面还有，
 * 需要更细就看别的页或先窄化操作」。
 */
final class SnapshotJson {

    private SnapshotJson() {
    }

    static ObjectNode encode(PageSnapshot snapshot) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("pageId", snapshot.pageId);
        out.put("url", snapshot.url);
        out.put("title", snapshot.title);
        out.put("snapshot", snapshot.snapshot);
        if (snapshot.text != null && !snapshot.text.isEmpty()) {
            out.put("text", snapshot.text);
        }
        out.put("truncated", snapshot.truncated);
        out.put("elapsedMs", snapshot.elapsedMs);
        return out;
    }

}
