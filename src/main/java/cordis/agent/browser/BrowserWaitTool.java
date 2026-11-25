package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/** {@code browser_wait}：干等一段时间后再读快照（懒加载 / 动画 / 异步请求） */
final class BrowserWaitTool implements Tool {

    private final BrowserService browser;

    BrowserWaitTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_wait";
    }

    @Override
    public String description() {
        return "等待一段时间后重新获取快照。页面内容异步加载、点完按钮结果还没出现时用它；"
                + "单次上限 60 秒，一般 1000~3000 毫秒就够。";
    }

    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"pageId\":{\"type\":\"string\",\"description\":\"browser_open 返回的 pageId\"},"
                + "\"ms\":{\"type\":\"integer\",\"description\":\"等待毫秒数，默认 1500\"}},"
                + "\"required\":[\"pageId\"]}";
    }

    @Override
    public String run(String arguments) throws Exception {
        JsonNode args = Json.MAPPER.readTree(arguments);
        return Json.MAPPER.writeValueAsString(SnapshotJson.encode(browser.waitSync(
                Json.required(args, "pageId"), args.path("ms").asLong(1500))));
    }

}
