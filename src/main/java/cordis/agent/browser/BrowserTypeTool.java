package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/** {@code browser_type}：往输入框填字，可选回车提交，返回操作后的新快照 */
final class BrowserTypeTool implements Tool {

    private final BrowserService browser;

    BrowserTypeTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_type";
    }

    @Override
    public String description() {
        return "往输入框/文本域里填入文本（先清空原有内容），submit=true 时按回车提交，"
                + "返回操作后的新快照。搜索框输入关键词就靠它。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"pageId\":{\"type\":\"string\",\"description\":\"browser_open 返回的 pageId\"},"
                + "\"ref\":{\"type\":\"string\",\"description\":\"输入框在快照里的 ref\"},"
                + "\"text\":{\"type\":\"string\",\"description\":\"要填入的文本\"},"
                + "\"submit\":{\"type\":\"boolean\",\"description\":\"填完是否按回车提交，默认 false\"}},"
                + "\"required\":[\"pageId\",\"ref\",\"text\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = Json.MAPPER.readTree(arguments);
        return Json.MAPPER.writeValueAsString(SnapshotJson.encode(browser.typeSync(
                Json.required(args, "pageId"),
                Json.required(args, "ref"),
                args.path("text").asText(""),
                args.path("submit").asBoolean(false))));
    }

}
