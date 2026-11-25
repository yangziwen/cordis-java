package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/**
 * {@code browser_screenshot}：整页截图落盘，返回路径。
 *
 * <p>模型看不到图片，但人可以打开工作区里的 png 核对；对「页面长这样但快照里
 * 没有文字」的场景（canvas 图表、验证码）这是唯一的取证手段。
 */
final class BrowserScreenshotTool implements Tool {

    private final BrowserService browser;

    BrowserScreenshotTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_screenshot";
    }

    @Override
    public String description() {
        return "给当前页面截图并保存到工作区，返回文件路径。用于人工核对、"
                + "或者页面内容是 canvas/图片而快照里读不到文字时。";
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
        String path = browser.screenshotSync(Json.required(args, "pageId"));
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("pageId", args.get("pageId").asText());
        out.put("path", path);
        return Json.MAPPER.writeValueAsString(out);
    }

}
