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

    private final MediaType json = MediaType.parse("application/json; charset=utf-8");

    public HttpService(Context ctx, OkHttpClient client) {
        super(ctx, "http");
        this.client = client;
    }

    /** 底层客户端（需要配置拦截器、连接池等高级能力时使用） */
    public OkHttpClient client() {
        return client;
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

    @Override
    public String toString() {
        return "HttpService(" + client.dispatcher().getClass().getSimpleName() + ")";
    }

}
