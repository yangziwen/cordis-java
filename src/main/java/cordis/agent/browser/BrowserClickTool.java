package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/** {@code browser_click}：按快照里的 ref 点击，返回点击后的新快照 */
final class BrowserClickTool implements Tool {

    private final BrowserService browser;

    BrowserClickTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_click";
    }

    @Override
    public String description() {
        return "点击页面上某个元素（ref 来自快照里的 [ref=eN]），返回点击后的新快照。"
                + "点链接、点按钮、点「下一页」都用它。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"pageId\":{\"type\":\"string\",\"description\":\"browser_open 返回的 pageId\"},"
                + "\"ref\":{\"type\":\"string\",\"description\":\"快照里的 ref，如 e12\"}},"
                + "\"required\":[\"pageId\",\"ref\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = Json.MAPPER.readTree(arguments);
        return Json.MAPPER.writeValueAsString(SnapshotJson.encode(
                browser.clickSync(Json.required(args, "pageId"), Json.required(args, "ref"))));
    }

}
