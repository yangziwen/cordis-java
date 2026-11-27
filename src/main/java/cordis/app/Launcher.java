package cordis.app;

/**
 * fat jar 的统一入口（Manifest Main-Class），按第一个参数分发：
 *
 * <pre>
 *   java -jar cordis-java.jar server --port 9000 --root ./data
 *   java -jar cordis-java.jar cli http://127.0.0.1:9000 --session my
 * </pre>
 *
 * <p>为什么需要它：fat jar 只有一个 Main-Class，而项目有两个入口
 * （{@link AgentServer} / {@link AgentCli}）。与其在文档里教两种启动方式
 * （{@code java -jar} 与 {@code java -cp jar 主类名}），不如在 jar 内部
 * 用第一个参数选好入口，剩下的参数原样转发——两个入口自己的参数解析
 * （含用法输出与退出码）完全复用。
 *
 * <p>demo 类不在分发范围内，但 fat jar 本身就是完整 classpath：
 * {@code java -cp cordis-java.jar cordis.demo.MemoryDemo} 照样能跑。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        if ("server".equals(command)) {
            AgentServer.main(rest);
        } else if ("cli".equals(command)) {
            AgentCli.main(rest);
        } else {
            System.err.println("[launcher] unknown command: " + command);
            usage();
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("用法: java -jar cordis-java.jar <server|cli> [选项...]");
        System.out.println();
        System.out.println("  server   启动 agent 服务（选项见 AgentServer --help："
                + "--port --root --base-url --model ...）");
        System.out.println("  cli      连接 agent 服务的控制台客户端"
                + "（选项见 AgentCli --help：--session --resume --no-stream）");
        System.out.println();
        System.out.println("  例: java -jar cordis-java.jar server --port 9000 --root ./data");
        System.out.println("      java -jar cordis-java.jar cli http://127.0.0.1:9000");
    }
}
