package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;
import cordis.browser.PageSnapshot;

/**
 * {@code browser_open}：打开网页，返回首屏快照。
 *
 * <p>返回的 {@code snapshot} 是无障碍树，每个可交互节点带 {@code [ref=eN]}；
 * 模型之后用这些 ref 点击或输入。{@code waitMs} 用来等 SPA 首屏渲染。
 */
final class BrowserOpenTool implements Tool {

    private final BrowserService browser;

    BrowserOpenTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_open";
    }

    @Override
    public String description() {
        return "用无头浏览器打开网页，返回页面标题、地址和带 [ref=eN] 的无障碍树快照。"
                + "内容由 JS 渲染、需要登录态、需要点击才出结果时用它；"
                + "纯静态页面读正文用 http_get 更快。只有白名单内的 http/https 地址能打开。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\",\"description\":\"完整的 http/https 地址\"},"
                + "\"waitMs\":{\"type\":\"integer\",\"description\":"
                + "\"导航完成后额外等待的毫秒数，SPA 建议 1500~3000，默认 1000\"}},"
                + "\"required\":[\"url\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = Json.MAPPER.readTree(arguments);
        PageSnapshot snapshot = browser.openSync(Json.required(args, "url"),
                args.path("waitMs").asLong(1000));
        return Json.MAPPER.writeValueAsString(SnapshotJson.encode(snapshot));
    }

}
