package cordis.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class Maps {

    private Maps() {}

    /**
     * Java 8 兼容的 map 构造辅助（对应 Map.of），允许 null 值。
     *
     * 参数必须成对出现：奇数个参数说明漏写了某个 key/value，
     * 直接 fail-fast 而不是在越界处抛一个看不出原因的 AIOOBE。
     */
    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Maps.of expects key/value pairs, but got " + kv.length + " arguments");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

}
