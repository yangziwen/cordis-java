package cordis.agent;

/**
 * 一个可供模型调用的工具（function calling 的"function"）。
 *
 * <p>前三个方法是<b>给模型看的</b>（编译进请求里的 function 定义）；{@link #run}
 * 是<b>给框架调的</b>（AgentService 在收到调用请求后执行）。
 *
 * <h3>run 的线程契约</h3>
 *
 * <p>run 在扩展池（{@code cordis-worker}）线程上被<b>同步</b>调用，允许阻塞
 * ——查库、算密、调外部接口都可以直接写。正因如此，禁止在 run 里等待主循环
 * 上的结果（例如 {@code Promise.join()}），那会自己等自己：主循环的回调排在
 * 主循环队列里，而主循环并不受本次调用影响——虽然不会死锁，但没有任何收益。
 *
 * <p>需要并发执行多个工具时，由调用方（AgentService）决定；单次 run 内部的
 * 并发由工具实现自己负责。
 */
public interface Tool {

    /** 工具名；同时是 ToolService 中的注册键，全局唯一 */
    String name();

    /** 给模型的自然语言描述：什么情况下该用这个工具 */
    String description();

    /**
     * 参数的 JSON Schema 文本，例如
     * {@code {"type":"object","properties":{"a":{"type":"number"}}}}。
     * 返回 null 表示无参数工具。
     */
    String parametersSchema();

    /**
     * 执行工具。arguments 是模型产出的完整 JSON 文本，解析责任在实现方；
     * 返回值会原样回填给模型（约定为 JSON 文本，方便模型理解）。
     *
     * @throws Exception 失败会作为该次调用的结果回填给模型（错误对模型可见，
     *                   由模型决定重试还是改道），不会中断整个 agent loop
     */
    String run(String arguments) throws Exception;

}
