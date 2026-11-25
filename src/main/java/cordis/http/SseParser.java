package cordis.http;

/**
 * 逐行解析 SSE 的增量状态机，一行输入、零或一条消息输出。
 *
 * <p>与规范（WHATWG HTML "server-sent events"）对应的规则：
 *
 * <ul>
 *   <li>空行 → 派发当前累积的消息，然后清空 data / event（id 按规范保留）；</li>
 *   <li>以 {@code :} 开头的行是注释（心跳包常用），整行忽略；</li>
 *   <li>{@code field: value}，冒号后恰好一个空格被剔除；没有冒号时整行是
 *       字段名、值为空串；</li>
 *   <li>多条 {@code data} 以 {@code \n} 拼接；</li>
 *   <li>没有 data 字段的消息块不派发（只有 id 或只有 event 都不算）；</li>
 *   <li>{@code retry} 字段与重连相关，本实现不做自动重连，故忽略。</li>
 * </ul>
 *
 * <p>本类不是线程安全的，但同一条流只会在一个 Dispatcher 线程上读取，
 * 因此无需同步。拆成独立类是为了能脱离网络单测解析逻辑。
 */
final class SseParser {

    private final StringBuilder data = new StringBuilder();

    private String id;

    private String event;

    private boolean hasData;

    /**
     * 喂入一行（不含换行符），返回需要派发的消息；无消息返回 null。
     */
    SseEvent accept(String line) {
        if (line.isEmpty()) {
            return dispatch();
        }
        if (line.charAt(0) == ':') {
            return null;
        }
        int colon = line.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
        }
        if ("data".equals(field)) {
            if (hasData) {
                data.append('\n');
            }
            data.append(value);
            hasData = true;
        } else if ("event".equals(field)) {
            event = value;
        } else if ("id".equals(field)) {
            id = value;
        }
        return null;
    }

    /** 流结束时调用：把没有以空行收尾的最后一条消息也派发出去 */
    SseEvent flush() {
        return dispatch();
    }

    private SseEvent dispatch() {
        if (!hasData) {
            // 只有 id/event 的空块：按规范丢弃，但 event 需要重置
            event = null;
            return null;
        }
        SseEvent result = new SseEvent(id, event, data.toString());
        data.setLength(0);
        hasData = false;
        event = null;
        return result;
    }

}
