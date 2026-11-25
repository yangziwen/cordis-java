package cordis.http;

/**
 * SSE 事件回调。
 *
 * <p><b>线程约束</b>：回调运行在 OkHttp 的 Dispatcher 线程上，<b>不是</b>主循环，
 * 也不是框架的扩展池。因此：
 *
 * <ul>
 *   <li>禁止在这里做阻塞 IO——会拖住整条连接的分发；</li>
 *   <li>要修改插件状态、触发事件、推进状态机，请用
 *       {@code ctx.scheduler.execute(...)} 切回主循环；</li>
 *   <li>可以安全读取 {@code ctx.get(...)}，服务仓库是并发容器；</li>
 *   <li>抛出运行时异常会取消本次流，异常本身成为 completion 的失败原因。</li>
 * </ul>
 *
 * @see HttpService#stream(okhttp3.Request, SseListener)
 */
@FunctionalInterface
public interface SseListener {

    void onEvent(SseEvent event);

}
