package cordis.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cordis.agent.Tool;
import cordis.browser.BrowserService;

/** {@code browser_close}：关掉页面，释放它的 BrowserContext（cookie 一起清掉） */
final class BrowserCloseTool implements Tool {

    private final BrowserService browser;

    BrowserCloseTool(BrowserService browser) {
        this.browser = browser;
    }

    @Override
    public String name() {
        return "browser_close";
    }

    @Override
    public String description() {
        return "关闭一个浏览器页面并释放它的上下文。任务完成后请关掉，"
                + "否则页面会一直占着内存（服务端也有 LRU 上限，但别依赖它）。";
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
        String pageId = args.path("pageId").asText("");
        boolean closed = browser.closeSync(pageId);
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("pageId", pageId);
        out.put("closed", closed);
        return Json.MAPPER.writeValueAsString(out);
    }

}
