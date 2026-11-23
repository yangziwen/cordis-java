package cordis.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class Maps {

    private Maps() {}

    /** Java 8 兼容的 map 构造辅助（对应 Map.of），允许 null 值 */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

}
