package cordis.agent;

import java.util.List;

/**
 * 模型客户端抽象：一次非流式的对话推进。
 *
 * <p>实现契约：
 *
 * <ul>
 *   <li><b>线程</b>：在扩展池（{@code cordis-worker}）线程上被 AgentService
 *       <b>同步</b>调用，允许阻塞到网络返回——这是刻意的设计：loop 跑在
 *       worker 上，主循环不被触碰；</li>
 *   <li><b>职责边界</b>：收到全部历史与当前可用工具快照，返回模型的决定。
 *       不负责历史管理、不负责工具执行、不负责重试——那些是 AgentService
 *       的事；</li>
 *   <li><b>流式</b>：接真实模型时，SSE 的增量 {@code tool_calls} 片段先经
 *       {@link ToolCallAggregator} 归并成完整 {@link ToolCall}，再从本接口
 *       返回——把"流式怎么拼"隔离在实现内部，loop 对流式与否无感知。</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * @param history 截至当前的全部消息（实现方不得修改）
     * @param tools   本轮可见的工具（快照，顺序稳定）
     * @throws Exception 网络 / 鉴权 / 响应解析失败；AgentService 会把它
     *                   变成本次 run 的 rejected，不会重试
     */
    LlmReply chat(List<LlmMessage> history, List<Tool> tools) throws Exception;

}
