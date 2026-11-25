package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/** {@code browser_press}：发送按键（Enter / Tab / Escape / ArrowDown / PageDown） */
final class BrowserPressTool implements Tool {

    private final BrowserService browser;

    BrowserPressTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_press";
    }

    @Override
    public String description() {
        return "在当前焦点上按一个键（Enter、Tab、Escape、ArrowDown、PageDown 等），"
                + "返回操作后的新快照。适合翻页、关闭弹窗、触发搜索。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"pageId\":{\"type\":\"string\",\"description\":\"browser_open 返回的 pageId\"},"
                + "\"key\":{\"type\":\"string\",\"description\":\"键名，默认 Enter\"}},"
                + "\"required\":[\"pageId\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = Json.MAPPER.readTree(arguments);
        String key = args.path("key").asText("Enter");
        return Json.MAPPER.writeValueAsString(SnapshotJson.encode(
                browser.pressSync(Json.required(args, "pageId"), key)));
    }

}
