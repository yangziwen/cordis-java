package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/** {@code browser_snapshot}：重新读一次当前页面的快照（内容变了但没做操作时用） */
final class BrowserSnapshotTool implements Tool {

    private final BrowserService browser;

    BrowserSnapshotTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_snapshot";
    }

    @Override
    public String description() {
        return "重新获取某个页面当前的无障碍树快照（例如页面自己刷新了、或者最后一次操作没返回快照）。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"pageId\":{\"type\":\"string\",\"description\":\"browser_open 返回的 pageId\"}},"
                + "\"required\":[\"pageId\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = Json.MAPPER.readTree(arguments);
        return Json.MAPPER.writeValueAsString(
                SnapshotJson.encode(browser.snapshotSync(Json.required(args, "pageId"))));
    }

}
