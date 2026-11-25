package cordis.agent;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;

/**
 * 把流式分片 {@link ToolCallDelta} 归并成完整 {@link ToolCall} 的状态机。
 *
 * <p>规则（对应 OpenAI 流式的实际行为）：
 *
 * <ul>
 *   <li>每个 {@code index} 是一个独立的调用槽位，分片可能<b>乱序到达</b>
 *       （并发工具调用时），按槽位而非到达顺序归并；</li>
 *   <li>{@code id} / {@code name} 在首个分片出现一次，后续为 null；</li>
 *   <li>{@code argumentsFragment} 是纯追加，中途<b>不可能被解析</b>；</li>
 *   <li>流的结束标志（OpenAI 的 {@code finish_reason="tool_calls"} 或
 *       {@code data: [DONE]}）由调用方判断，之后调 {@link #complete()}；
 *       同样地，没有以分片收尾的 tail 语义不存在——没有"半个 tool call"
 *       这种合法产物，缺字段就是错误。</li>
 * </ul>
 *
 * <p>{@link #complete()} 会 fail-fast：某个槽位没收到过 name（流被中途截断）
 * 直接抛异常，而不是交付一个 name 为 null 的残次调用——残次调用会在工具
 * dispatch 时变成"unknown tool"，错误离根因隔了整整一轮。
 *
 * <p>线程模型：单条流只在 OkHttp 的一个 Dispatcher 线程上读，聚合器只在那
 * 个线程被调用，故不加同步；跨流复用各自持有实例。
 */
public final class ToolCallAggregator {

    private final TreeMap<Integer, Slot> slots = new TreeMap<>();

    private static final class Slot {

        String id;

        String name;

        final StringBuilder arguments = new StringBuilder();

    }

    /** 喂入一个分片；重复的 id / name 以首个为准 */
    public void add(ToolCallDelta delta) {
        Slot slot = slots.get(delta.index);
        if (slot == null) {
            slot = new Slot();
            slots.put(delta.index, slot);
        }
        if (slot.id == null && delta.id != null) {
            slot.id = delta.id;
        }
        if (slot.name == null && delta.name != null) {
            slot.name = delta.name;
        }
        if (delta.argumentsFragment != null) {
            slot.arguments.append(delta.argumentsFragment);
        }
    }

    /**
     * 流结束后取出全部完整调用，按 index 升序（即模型请求的顺序）。
     *
     * @throws IllegalStateException 任何槽位缺少 name（流被截断）
     */
    public List<ToolCall> complete() {
        List<ToolCall> result = new ArrayList<>(slots.size());
        for (Map.Entry<Integer, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (slot.name == null) {
                throw new IllegalStateException("tool call #" + entry.getKey()
                        + " is incomplete: name never arrived (stream truncated?)");
            }
            result.add(new ToolCall(slot.id, slot.name, slot.arguments.toString()));
        }
        slots.clear();
        return result;
    }

    /** 已收到的槽位数（诊断用） */
    public int slotCount() {
        return slots.size();
    }

}
