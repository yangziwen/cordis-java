package cordis.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTTP 工具能访问哪些地址——「模型可以访问的网络范围」这一道关口。
 *
 * <p>与 {@link cordis.fs.ApprovalGate} 同构，但守的是另一件事：文件写审批
 * 防的是「改坏用户数据」，这里防的是「模型把数据发出去 / 探内网」。默认
 * <b>拒绝一切</b>，装配方显式放开。
 *
 * <p>为什么不把限制做进 {@code HttpService}：它是通用客户端，程序自己写的
 * 调用（例如 wire 层调模型网关）不该被业务白名单约束；而
 * {@code http_get}/{@code http_post} 的参数来自模型，所以必须过这道关口。
 *
 * <p>重定向也逐跳校验（见 {@code HttpCaller}）：只校验首个 URL 的话，一个
 * 白名单内的地址 302 到内网就绕过了整个策略。
 */
@FunctionalInterface
public interface HttpAccessPolicy {

    /**
     * @param target 即将发起的请求目标（重定向的每一跳都会问一次）
     * @return true 才允许请求
     */
    boolean allow(URI target);

    /**
     * 是否“全开”（任意主机）。只给运维面用：banner / {@code /health} 据此
     * 把“白名单”与“全开”区分开，避免有人以为自己在白名单模式下运行。
     */
    default boolean open() {
        return false;
    }

    /** 拒绝一切；也是未配置时的默认值 */
    static HttpAccessPolicy denyAll() {
        return target -> false;
    }

    /** 放行一切（仅建议本地调试 / 完全可信环境使用） */
    static HttpAccessPolicy allowAll() {
        return new HttpAccessPolicy() {

            @Override
            public boolean allow(URI target) {
                return true;
            }

            @Override
            public boolean open() {
                return true;
            }

        };
    }

    /**
     * 按主机白名单放行，只接受 {@code http}/{@code https}。
     *
     * <p>模式写法：
     *
     * <ul>
     *   <li>{@code example.com}：精确匹配该主机，<b>端口不限</b>；</li>
     *   <li>{@code example.com:8080}：精确匹配主机与端口；</li>
     *   <li>{@code *.example.com}：匹配任意子域（{@code a.example.com}），
     *       <b>不</b>匹配 {@code example.com} 本身；</li>
     *   <li>{@code *}：放行<b>任意主机</b>（仍只限 {@code http}/{@code https}）。
     *       等价于 {@link #allowAll()}，用于本地调试；{@code *:8080} 则只放行
     *       所有主机的 8080 端口。</li>
     * </ul>
     *
     * <p>主机名比较不区分大小写；URL 未写端口时按协议默认端口（80/443）
     * 参与比较。IPv6 字面量写成 {@code [::1]:8080}。
     */
    static HttpAccessPolicy hosts(String... patterns) {
        final List<String> rules = new ArrayList<String>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.trim().isEmpty()) {
                rules.add(pattern.trim().toLowerCase(Locale.ROOT));
            }
        }
        final boolean open = rules.contains("*");
        return new HttpAccessPolicy() {

            @Override
            public boolean allow(URI target) {
                return matches(rules, target);
            }

            @Override
            public boolean open() {
                return open;
            }

        };
    }

    static boolean matches(List<String> rules, URI target) {
        String scheme = target.getScheme();
        if (scheme == null) {
            return false;
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = target.getHost();
        if (host == null) {
            return false;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        int port = target.getPort() > 0 ? target.getPort()
                : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
        for (String rule : rules) {
            String ruleHost = rule;
            Integer rulePort = null;
            int colon = rule.lastIndexOf(':');
            int bracket = rule.lastIndexOf(']');
            if (colon > bracket && colon > 0) {
                String digits = rule.substring(colon + 1);
                if (digits.matches("\\d+")) {
                    ruleHost = rule.substring(0, colon);
                    rulePort = Integer.valueOf(digits);
                }
            }
            if (rulePort != null && rulePort.intValue() != port) {
                continue;
            }
            if ("*".equals(ruleHost)) {
                return true;                                     // 任意主机（scheme 已限定）
            }
            if (ruleHost.startsWith("*.")) {
                String suffix = ruleHost.substring(1);           // ".example.com"
                if (lowerHost.endsWith(suffix) && lowerHost.length() > suffix.length()) {
                    return true;
                }
            } else if (ruleHost.equals(lowerHost)) {
                return true;
            }
        }
        return false;
    }

}
