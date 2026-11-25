package cordis.http;

/**
 * 一条 SSE（{@code text/event-stream}）消息，对应规范里的一个 event block。
 *
 * <p>一条消息由流里的若干行累积而成，遇到空行才派发：
 *
 * <pre>
 *   id: 42
 *   event: delta
 *   data: 第一行
 *   data: 第二行
 *   （空行 → 派发，data 为 "第一行\n第二行"）
 * </pre>
 *
 * <p>三个字段都可能为 null：{@code id} 与 {@code event} 是可选字段，缺省时
 * 为 null（规范里的默认值分别是"沿用上一个 id"和 {@code message}，这里不
 * 做替换，把原始信息留给调用方判断）。
 *
 * <p>本类不可变，可以安全地从 IO 线程交给任意其他线程。
 */
public final class SseEvent {

    /** 事件 id；流里未声明时为 null */
    public final String id;

    /** 事件类型；流里未声明时为 null（规范默认 {@code message}） */
    public final String event;

    /** 数据体；多行 data 字段以 {@code \n} 拼接 */
    public final String data;

    public SseEvent(String id, String event, String data) {
        this.id = id;
        this.event = event;
        this.data = data == null ? "" : data;
    }

    @Override
    public String toString() {
        return "SseEvent(" + (event == null ? "message" : event)
                + ", id=" + id + ", " + data.length() + " chars)";
    }

}
