package cordis.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import cordis.agent.AgentPlugin;
import cordis.agent.ConversationPlugin;
import cordis.agent.ConversationService;
import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.MemoryPlugin;
import cordis.agent.MemoryService;
import cordis.agent.SessionInfo;
import cordis.agent.Tool;
import cordis.agent.ToolCall;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.sqlite.SqlitePlugin;
import cordis.util.Maps;

/**
 * 记忆层演示：对话落库、业务插件整体重装后历史仍在、tool_calls 往返无损。
 *
 * <p>关键设计：<b>SQLite fiber 全程不动，只卸载并重装 agent / memory /
 * conversation 三个业务插件</b>。新的 MemoryService 实例内部没有任何缓存，
 * 读到的每一条都来自 DB 文件——比"重启 JVM"更轻，证明力等价。
 */
public final class MemoryDemo {

    private static final String SESSION = "A";

    private MemoryDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path dbFile = Files.createTempFile("cordis-memory-", ".db");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        Context root = Context.root();

        try {
            Fiber sqliteFiber = root.registry.use(SqlitePlugin.INSTANCE,
                    Maps.of("url", url), null).await().join();
            try {
                List<Fiber> business = assembleBusiness(root);
                sectionFirstTurns(root);

                for (Fiber fiber : business) {
                    fiber.dispose();
                }
                root.scheduler.awaitIdle();
                System.out.println("   业务插件已全部卸载（sqlite 保留）");
                System.out.println();

                business = assembleBusiness(root);
                sectionReload(root, dbFile);
                sectionThirdTurn(root);
                sectionToolCalls(root);
                sectionSessions(root);

                for (Fiber fiber : business) {
                    fiber.dispose();
                }
                root.scheduler.awaitIdle();
            } finally {
                sqliteFiber.dispose();
            }
        } finally {
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
            Files.deleteIfExists(dbFile);
        }
    }

    private static List<Fiber> assembleBusiness(Context root) {
        List<Fiber> fibers = new ArrayList<>();
        fibers.add(root.registry.use(MemoryPlugin.INSTANCE,
                Maps.of(), Maps.of("sqlite", true)).await().join());
        fibers.add(root.registry.use(AgentPlugin.INSTANCE,
                Maps.of("llm", new RememberLlm(), "maxSteps", 4), null).await().join());
        fibers.add(root.registry.use(ConversationPlugin.INSTANCE,
                Maps.of(), Maps.of("agent", true, "memory", true)).await().join());
        return fibers;
    }

    /** 1. 两轮对话，落库 */
    private static void sectionFirstTurns(Context root) {
        System.out.println("== 1. 会话 " + SESSION + "：两轮对话 ==");
        ConversationService conv = root.get("conversation");
        System.out.println("   第 1 轮: " + conv.send(SESSION, "我叫小明").completion().join().answer);
        System.out.println("   第 2 轮: " + conv.send(SESSION, "我叫什么？").completion().join().answer);
        System.out.println();
    }

    /** 2. 业务插件整体重装，历史只能来自 DB */
    private static void sectionReload(Context root, Path dbFile) throws Exception {
        System.out.println("== 2. 重装后读取历史（新 MemoryService 实例，无内存缓存）==");
        MemoryService memory = root.get("memory");
        List<LlmMessage> history = memory.load(SESSION).join();
        System.out.println("   读回 " + history.size() + " 条，DB 文件 "
                + Files.size(dbFile) + " 字节");
        for (LlmMessage message : history) {
            System.out.println("     " + message);
        }
        System.out.println();
    }

    /** 3. 第三轮：把落库的历史接回 loop */
    private static void sectionThirdTurn(Context root) {
        System.out.println("== 3. 第三轮：新装配的 agent 接着旧对话 ==");
        ConversationService conv = root.get("conversation");
        System.out.println("   第 3 轮: " + conv.send(SESSION, "我叫什么？").completion().join().answer);
        System.out.println();
    }

    /** 4. tool_calls 列往返：assistant 的工具调用原样存回 */
    private static void sectionToolCalls(Context root) {
        System.out.println("== 4. tool_calls 编码往返（SQLite 没有 JSON 列）==");
        MemoryService memory = root.get("memory");
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.user("6 乘 7"));
        String prettyJson = "{\n  \"a\": 6,\n  \"b\": 7\n}";   // 带真换行的参数
        messages.add(LlmMessage.assistant(LlmReply.toolCall("call_1", "math", prettyJson)));
        messages.add(LlmMessage.toolResult("call_1", "{\"product\":42}"));
        memory.appendAll("B", messages).join();

        List<LlmMessage> back = memory.load("B").join();
        ToolCall restored = back.get(1).toolCalls.get(0);
        boolean identical = "call_1".equals(restored.id)
                && "math".equals(restored.name)
                && prettyJson.equals(restored.arguments);
        System.out.println("   写入 3 条，读回 " + back.size() + " 条");
        System.out.println("   assistant 的工具调用: " + restored);
        System.out.println("   往返无损 = " + identical
                + "（参数含 2 个换行，落库时被转义、读出时还原）");
        System.out.println();
    }

    /** 5. 会话列表与删除 */
    private static void sectionSessions(Context root) {
        System.out.println("== 5. 会话列表 ==");
        MemoryService memory = root.get("memory");
        for (SessionInfo info : memory.sessions().join()) {
            System.out.println("   " + info.id + ": " + info.messageCount + " 条，最后更新 "
                    + new java.util.Date(info.updatedAt));
        }
        memory.delete(SESSION).join();
        List<SessionInfo> left = memory.sessions().join();
        System.out.println("   删除 " + SESSION + " 后剩余 " + left.size() + " 个会话: " + left);
        System.out.println();
    }

    /** 只做记忆：从历史里提取用户名字，用来证明前文确实被读到了 */
    private static final class RememberLlm implements LlmClient {

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools) {
            LlmMessage last = history.get(history.size() - 1);
            System.out.println("   [llm] 看到 " + history.size() + " 条历史，末条: " + last);
            String name = "无名氏";
            for (LlmMessage message : history) {
                if ("user".equals(message.role) && message.content.startsWith("我叫")
                        && !message.content.contains("我叫什么")) {
                    name = message.content.substring(2).trim();
                }
            }
            if (last.content.contains("我叫什么")) {
                return LlmReply.answer("你叫" + name);
            }
            return LlmReply.answer("你好，" + name + "。我记住了。");
        }

    }

}
