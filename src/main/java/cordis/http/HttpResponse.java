package cordis.http;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次 HTTP 调用的结果快照。
 *
 * 与 OkHttp 的 {@code Response} 不同，本类不持有连接与输入流：body 在回调
 * 线程里读取完毕、解码成字符串后才构造，因此可以安全地跨线程传给任意
 * fiber，调用方也无须（无法）关闭它。
 */
public final class HttpResponse {

    /** HTTP 状态码；不做 2xx 校验，判断交给调用方（与 OkHttp 语义一致） */
    public final int status;

    /** 响应头；同名字段可能有多个值，故保留 List */
    public final Map<String, List<String>> headers;

    /** 响应体文本（字符集取自 Content-Type，未声明时按 UTF-8） */
    public final String body;

    public HttpResponse(int status, Map<String, List<String>> headers, String body) {
        this.status = status;
        this.headers = headers == null
                ? Collections.<String, List<String>>emptyMap()
                : headers;
        this.body = body == null ? "" : body;
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    /** 取首个同名响应头，没有则返回 null */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public String toString() {
        return "HttpResponse(" + status + ", " + body.length() + " chars)";
    }

}
