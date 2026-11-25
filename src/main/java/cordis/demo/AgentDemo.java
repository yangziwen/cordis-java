package cordis.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cordis.agent.AgentCall;
import cordis.agent.AgentPlugin;
import cordis.agent.AgentResult;
import cordis.agent.AgentService;
import cordis.agent.LlmClient;
import cordis.agent.LlmMessage;
import cordis.agent.LlmReply;
import cordis.agent.Tool;
import cordis.agent.ToolCall;
import cordis.agent.ToolCallAggregator;
import cordis.agent.ToolCallDelta;
import cordis.agent.ToolComponent;
import cordis.core.Context;
import cordis.core.Fiber;
import cordis.util.Maps;

/**
 * agent 包演示：脚本 LLM 打通 loop、工具热插拔、可取消、流式分片聚合。
 *
 * <p>不接真实模型——LlmClient 由脚本回放实现（按 history 的最后一条消息
 * 决定下一步），把 loop / 装配 / 取消 / 容错全部先验证对，再谈网络。
 * demo 里对 JSON 的处理（正则提数）只为可运行，真实工具应引入 JSON 库。
 */
public final class AgentDemo {

    private AgentDemo() {
    }

    public static void main(String[] args) throws Exception {
        Context root = Context.root();

        // 事件观察：emit 从 worker 发出，监听器在主循环串行执行
        final cordis.core.Disposable stepHook = root.on(AgentService.EVT_STEP, a -> {
            System.out.println("   [step] 第 " + a[0] + " 轮，可见工具 " + a[1] + " 个");
            return null;
        });
        final cordis.core.Disposable toolHook = root.on(AgentService.EVT_TOOL, a -> {
            System.out.println("   [tool] " + a[0] + " -> " + a[1]);
            return null;
        });

        Fiber agentFiber = root.registry.use(AgentPlugin.INSTANCE,
                Maps.of("llm", new AdaptiveLlm(), "maxSteps", 6), null).await().join();
        Fiber mathFiber = root.registry.use(new ToolComponent(new MathTool()),
                Maps.of(), Maps.of("agent.tools", true)).await().join();
        Fiber clockFiber = root.registry.use(new ToolComponent(new ClockTool()),
                Maps.of(), Maps.of("agent.tools", true)).await().join();

        try {
            AgentService agent = root.get("agent");
            sectionLoop(agent);
            sectionHotSwap(root, agent, mathFiber);
            stepHook.dispose();                 // 取消节改用计数器，避免刷屏
            toolHook.dispose();
            sectionCancel(root, agent);
            sectionAggregate();
        } finally {
            agentFiber.dispose();
            clockFiber.dispose();
            root.scheduler.awaitIdle();
            root.scheduler.shutdown();
        }
    }

    /** 1. 基本流程：请求工具 -> 回填 -> 终答 */
    private static void sectionLoop(AgentService agent) {
        System.out.println("== 1. agent loop：工具调用 -> 回填 -> 终答 ==");
        List<LlmMessage> history = new ArrayList<>();
        AgentResult result = agent.run(history, "6 乘 7 是多少？").completion().join();
        System.out.println("   终答: " + result.answer);
        System.out.println("   终态: " + result.stopReason + "，" + result.steps + " 轮，"
                + result.toolCalls + " 次工具");
        System.out.println("   历史 " + history.size() + " 条: "
                + history.get(0).role + " -> " + history.get(1).role
                + " -> " + history.get(2).role + " -> " + history.get(3).role);
        System.out.println();
    }

    /**
     * 2. 工具热插拔 + 模型改道：卸掉 math 后模型仍请求它（旧快照语境），
     * 收到 unknown tool 回填，改用 clock 完成任务——两件事同时被验证。
     */
    private static void sectionHotSwap(Context root, AgentService agent, Fiber mathFiber) {
        System.out.println("== 2. 热插拔：卸载 math，模型改用 clock ==");
        mathFiber.dispose();
        root.scheduler.awaitIdle();

        List<LlmMessage> history = new ArrayList<>();
        AgentResult result = agent.run(history, "现在几点？顺便算下 2x3。").completion().join();
        System.out.println("   终答: " + result.answer);
        System.out.println("   终态: " + result.stopReason + "，" + result.steps + " 轮，"
                + result.toolCalls + " 次工具");
        System.out.println("   math 已卸载，期间模型请求 math 得到错误回填，转向 clock");
        System.out.println();
    }

    /** 3. 取消：dispose 后 loop 在轮间停止 */
    private static void sectionCancel(Context root, AgentService agent) throws Exception {
        System.out.println("== 3. 主动取消 ==");
        final AtomicInteger steps = new AtomicInteger();
        root.on(AgentService.EVT_STEP, a -> {
            steps.incrementAndGet();
            return null;
        });

        AgentCall call = agent.run(new ArrayList<>(), "一直算下去");
        Thread.sleep(500);                       // 让 loop 跑起来
        int stepsBefore = steps.get();
        call.dispose();

        String outcome;
        try {
            outcome = "unexpected: " + call.completion().join();
        } catch (CancellationException e) {
            outcome = e.toString();
        } catch (RuntimeException e) {
            outcome = "unexpected: " + e;
        }
        Thread.sleep(600);                       // 若 loop 没停，会继续跑轮
        System.out.println("   取消前执行了 " + stepsBefore + " 轮，取消后 600ms 内 = "
                + (steps.get() - stepsBefore) + " 轮（应为 0 或收尾 1 轮）");
        System.out.println("   completion: " + outcome);
        System.out.println();
    }

    /** 4. 流式 tool_calls 分片聚合：乱序喂入，归并出完整调用 */
    private static void sectionAggregate() {
        System.out.println("== 4. 流式 tool_calls 聚合回放 ==");
        ToolCallAggregator agg = new ToolCallAggregator();
        agg.add(new ToolCallDelta(0, "call_1", "weather", null));
        agg.add(new ToolCallDelta(0, null, null, "{\"ci"));                 // 不完整 JSON
        agg.add(new ToolCallDelta(1, "call_2", "clock", null));             // 乱序插入
        agg.add(new ToolCallDelta(0, null, null, "ty\":\"北京\"}"));
        agg.add(new ToolCallDelta(1, null, null, "{}"));
        for (ToolCall call : agg.complete()) {
            System.out.println("   " + call);
        }
        System.out.println("   5 个分片（含乱序与不完整 JSON 片段）-> 2 个完整 ToolCall");
        System.out.println();
    }

    // ---------- 脚本 LLM：按 history 最后一条消息决定下一步 ----------

    /**
     * 自适应脚本：请求工具 -> 看到结果决定改道或收尾。
     * sleep 模拟模型延迟，让取消窗口真实存在。
     */
    private static final class AdaptiveLlm implements LlmClient {

        @Override
        public LlmReply chat(List<LlmMessage> history, List<Tool> tools, String systemPrompt)
                throws Exception {
            Thread.sleep(120);
            StringBuilder names = new StringBuilder();
            for (Tool tool : tools) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(tool.name());
            }
            boolean endless = "一直算下去".equals(history.get(0).content);
            if (!endless) {
                System.out.println("   [llm] 工具可见集: [" + names + "]");
            }
            // 取消节的输入：模型永远不知足，一轮接一轮地要工具
            if (endless) {
                return LlmReply.toolCall("call_" + history.size(), "clock", "{}");
            }
            LlmMessage last = history.get(history.size() - 1);
            if ("tool".equals(last.role)) {
                if (last.content.contains("unknown tool")) {
                    // 数学工具没了，换 clock 完成剩下的任务
                    return LlmReply.toolCall("call_" + history.size(), "clock", "{}");
                }
                return LlmReply.answer("算好了：" + last.content);
            }
            // 首轮一律先要 math
            return LlmReply.toolCall("call_" + history.size(), "math", "{\"a\":6,\"b\":7}");
        }

    }

    // ---------- 工具实现 ----------

    private static final class MathTool implements Tool {

        @Override
        public String name() {
            return "math";
        }

        @Override
        public String description() {
            return "计算两个数的乘积";
        }

        @Override
        public String parametersSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},"
                    + "\"required\":[\"a\",\"b\"]}";
        }

        @Override
        public String run(String arguments) {
            double a = num(arguments, "a");
            double b = num(arguments, "b");
            return String.format(Locale.ROOT, "{\"product\":%.0f}", a * b);
        }

    }

    private static final class ClockTool implements Tool {

        @Override
        public String name() {
            return "clock";
        }

        @Override
        public String description() {
            return "查询当前时间";
        }

        @Override
        public String parametersSchema() {
            return null;
        }

        @Override
        public String run(String arguments) {
            return "{\"time\":\"" + java.time.LocalTime.now().withNano(0) + "\"}";
        }

    }

    /** demo 级参数提取；真实实现请用 JSON 库 */
    private static double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(\\.\\d+)?)").matcher(json);
        if (!m.find()) {
            return 0;
        }
        return Double.parseDouble(m.group(1));
    }

}
