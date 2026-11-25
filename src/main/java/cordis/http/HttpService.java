package cordis.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import cordis.core.Context;
import cordis.core.Deferred;
import cordis.core.Promise;
import cordis.core.Service;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * HTTP 客户端服务：把 OkHttp 的异步调用桥接成框架的一等提案 {@link Promise}。
 *
 * <h3>为什么不走 {@code scheduler.offload}</h3>
 *
 * 框架提供两种把阻塞工作移出主循环的方式，本插件选了后者：
 *
 * <ul>
 *   <li><b>offload</b>：把整段阻塞调用塞进扩展池线程，占住一个 worker 直到
 *       调用返回。适合 JDBC 这类“驱动只有同步 API”的场景；</li>
 *   <li><b>原生异步回调</b>（本类）：OkHttp 的 {@code enqueue} 在自己维护的
 *       Dispatcher 线程池里发起请求，完成后回调。既不占扩展池，也不受
 *       {@code blockingPool} 线程数的限制，并发能力由 OkHttp 的
 *       {@code maxRequests} 决定。</li>
 * </ul>
 *
 * 回调线程不是主循环线程，但 {@link Deferred#resolve} 允许跨线程完成——
 * 内部会经 {@code scheduler.execute} 投递回主循环，因此下游的
 * {@code then/catchError} 仍然串行执行，无需任何额外同步。
 *
 * <h3>线程安全</h3>
 *
 * OkHttpClient 本身线程安全且应当共享，因此一个 HttpService 实例可以被
 * 任意多个 fiber 并发调用；每次 {@link #execute} 都会新建独立的 Call。
 */
public class HttpService extends Service {

    /** 由插件构建并注入，生命周期与插件一致 */
    private final OkHttpClient client;

    /**
     * 流式专用客户端：与 {@link #client} 各自持有一个 Dispatcher，
     * 因此长连接打满自己的并发额度时，普通请求不受任何影响。
     */
    private final OkHttpClient streamClient;

    private final MediaType json = MediaType.parse("application/json; charset=utf-8");

    public HttpService(Context ctx, OkHttpClient client, OkHttpClient streamClient) {
        super(ctx, "http");
        this.client = client;
        this.streamClient = streamClient;
    }

    /** 底层客户端（需要配置拦截器、连接池等高级能力时使用） */
    public OkHttpClient client() {
        return client;
    }

    /** 流式专用客户端 */
    public OkHttpClient streamClient() {
        return streamClient;
    }

    /**
     * 发起一次请求，返回在<b>任意线程</b>结算的 Promise。
     * 网络层失败（连接拒绝、超时、DNS 失败、读 body 出错）走 reject；
     * HTTP 非 2xx 不算失败，按 OkHttp 语义正常 resolve。
     */
    public Promise<HttpResponse> execute(final Request request) {
        final Deferred<HttpResponse> deferred = ctx.scheduler.defer();
        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onResponse(Call call, Response response) {
                // body() 在 3.x 可空；读流会抛 IOException，统一转成 rejected
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    Map<String, List<String>> headers = response.headers().toMultimap();
                    deferred.resolve(new HttpResponse(response.code(), headers, text));
                } catch (IOException e) {
                    deferred.reject(e);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                deferred.reject(e);
            }

        });
        return deferred.promise();
    }

    public Promise<HttpResponse> get(String url) {
        return execute(new Request.Builder().url(url).get().build());
    }

    public Promise<HttpResponse> postJson(String url, String jsonBody) {
        RequestBody body = RequestBody.create(json, jsonBody);
        return execute(new Request.Builder().url(url).post(body).build());
    }

    /**
     * 建立一次 SSE 订阅（{@code text/event-stream}），事件到达即回调。
     *
     * <p><b>与 {@link #execute} 的区别是"什么时候算完成"</b>：普通请求在 body
     * 读完时一次性结算；SSE 的 body 是无限长的，边读边派发，永远不"读完"，
     * 只能被对端关闭、网络中断或调用方取消。因此这里返回的不是
     * {@code Promise<结果>}，而是一个可取消的句柄 {@link SseCall}：
     * 事件走 listener 的推送通道，结束走 {@code completion()}。
     *
     * <p><b>线程</b>：整个读循环跑在 OkHttp Dispatcher 线程上（一次订阅长期
     * 占用该池的一个线程，这是长连接的固有成本，也是流式流量单独走一个
     * client 的原因）。listener 的约束见 {@link SseListener}。
     *
     * <p>HTTP 非 2xx 视为失败，body 前若干字符会带在异常消息里；2xx 但不是
     * {@code text/event-stream} 也照常解析——有些网关会改 Content-Type，
     * 内容对不对由读到的行决定，不额外设卡。
     */
    public SseCall stream(final Request request, final SseListener listener) {
        final Call call = streamClient.newCall(request);
        final Deferred<Void> completion = ctx.scheduler.defer();
        final SseCall sse = new SseCall(call, completion);

        call.enqueue(new Callback() {

            @Override
            public void onResponse(Call ignored, Response response) {
                if (sse.isDisposed()) {
                    response.close();
                    return;
                }
                if (!response.isSuccessful()) {
                    sse.fail(new IOException("sse request failed: HTTP " + response.code()
                            + " " + snippet(response)));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    sse.fail(new IOException("sse response has no body"));
                    return;
                }
                // 读循环直到流结束或被 cancel() 打断（后者会抛 IOException）
                try (ResponseBody closing = body) {
                    SseParser parser = new SseParser();
                    BufferedSource source = closing.source();
                    String line;
                    while (!sse.isDisposed() && (line = source.readUtf8Line()) != null) {
                        SseEvent event = parser.accept(line);
                        if (event == null) {
                            continue;
                        }
                        try {
                            listener.onEvent(event);
                        } catch (RuntimeException e) {
                            // 监听器自己炸了：连带掐断连接，避免空转读数据
                            sse.fail(e);
                            call.cancel();
                            return;
                        }
                    }
                    if (!sse.isDisposed()) {
                        SseEvent tail = parser.flush();
                        if (tail != null) {
                            listener.onEvent(tail);
                        }
                        sse.completeOk();
                    }
                } catch (IOException e) {
                    // 被取消时的 IOException 是 cancel 的副产物，不算失败
                    sse.fail(e);
                }
            }

            @Override
            public void onFailure(Call ignored, IOException e) {
                sse.fail(e);
            }

        });
        return sse;
    }

    /** 取错误响应体的前 200 字符，避免把一个 HTML 错误页整个塞进异常消息 */
    private static String snippet(Response response) {
        try (ResponseBody body = response.body()) {
            if (body == null) {
                return "";
            }
            String text = body.string();
            return text.length() > 200 ? text.substring(0, 200) + "..." : text;
        } catch (IOException e) {
            return "<unreadable body: " + e + ">";
        }
    }

    @Override
    public String toString() {
        return "HttpService(" + client.dispatcher().getClass().getSimpleName() + ")";
    }

}
