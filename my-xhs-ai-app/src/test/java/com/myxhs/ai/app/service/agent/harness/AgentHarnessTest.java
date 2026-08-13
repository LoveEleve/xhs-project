package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.MetricToolAccess;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentHarness 决策循环单元测试（Fake 模型 + Fake 工具，确定性驱动，无 DB）。
 * 覆盖：正常闭环（存在性校验通过）、编造证据被拒重想、顽固编造→EVIDENCE_INVALID、
 * 策略拒绝耗尽、同参数死循环、预算步骤耗尽、模型不可用降级、格式纠正重想。
 */
class AgentHarnessTest {

    private static final Pattern EV_PATTERN = Pattern.compile("证据 id=(ev_\\w+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 假工具：同参数返回同结果（保证语义去重生效），带 window 便于区分证据 */
    private static final class FakeMetricTools implements MetricToolAccess {
        @Override
        public String queryOrderVolume(String window) {
            return "volume=61 window=" + window;
        }

        @Override
        public String paymentSuccessRate(String window) {
            return "rate=0.5 window=" + window;
        }

        @Override
        public String contentInteraction(String window) {
            return "interaction=48 window=" + window;
        }

        @Override
        public String baselineWindow(String window) {
            return "baseline.window current=" + window;
        }
    }

    /** 假模型：按对话内容确定性返回决策 JSON；"THROW" 前缀=模拟模型故障 */
    private static final class FakeDecisionModel implements ChatModel {
        private final Function<List<String>, String> responder;
        private final AtomicInteger calls = new AtomicInteger();

        FakeDecisionModel(Function<List<String>, String> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            List<String> texts = request.messages().stream().map(AgentHarnessTest::messageText).toList();
            String json = responder.apply(texts);
            if (json.startsWith("THROW")) {
                throw new RuntimeException("模型服务不可用");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(10, 5)).modelName("fake").build())
                    .build();
        }
    }

    /** 消息文本提取（1.0 消息模型：AiMessage.text() / UserMessage.singleText()） */
    private static String messageText(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            return ai.text();
        }
        if (m instanceof UserMessage u) {
            return u.singleText();
        }
        return m.toString();
    }

    private static AgentHarness harness(Function<List<String>, String> responder, AgentBudget budget) {
        return new AgentHarness(new FakeDecisionModel(responder), new FakeMetricTools(),
                MAPPER, budget, 0.002, 2);
    }

    /** 从对话中取最后一条工具结果消息的证据 id */
    private static String lastEvId(List<String> texts) {
        String found = null;
        for (String t : texts) {
            Matcher m = EV_PATTERN.matcher(t);
            if (m.find()) {
                found = m.group(1);
            }
        }
        return found;
    }

    /** 从对话中取全部工具结果证据 id（按出现顺序） */
    private static List<String> allEvIds(List<String> texts) {
        List<String> ids = new java.util.ArrayList<>();
        for (String t : texts) {
            Matcher m = EV_PATTERN.matcher(t);
            while (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    private static String toolCallJson(String tool, String window) {
        return "{\"action\":\"TOOL_CALL\",\"tool\":\"" + tool
                + "\",\"args\":{\"window\":\"" + window + "\"},\"reasoning\":\"查窗口数据\"}";
    }

    private static String answerJson(String conclusion, String evId) {
        return "{\"action\":\"ANSWER\",\"conclusion\":\"" + conclusion
                + "\",\"evidenceRefs\":[\"" + evId + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"演示数据\"}";
    }

    @Test
    void 正常闭环_工具证据通过存在性校验() {
        AgentHarness h = harness(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("当前窗口下单量为61，环比上周同窗口下降10%", ev);
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertEquals(TerminationReason.COMPLETED, run.terminationReason());
        assertTrue(run.finalAnswer().contains("下单量为61"));
        assertTrue(run.finalAnswer().contains("证据链"));
        assertTrue(run.finalAnswer().contains("反证：无"));
        assertEquals(1, run.evidenceChain().size());
    }

    @Test
    void 基线确定性化_先算基线窗口再对比() {
        AgentHarness h = harness(texts -> {
            long toolResults = texts.stream().filter(t -> t.contains("证据 id=")).count();
            if (toolResults == 0) {
                return toolCallJson("baselineWindow", "2026-08-01~2026-08-07");
            }
            if (toolResults == 1) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            if (toolResults == 2) {
                return toolCallJson("queryOrderVolume", "2026-07-25~2026-07-31");
            }
            List<String> evs = allEvIds(texts);
            String refs = String.join("\",\"", evs);
            return "{\"action\":\"ANSWER\",\"conclusion\":\"基线窗口由 baselineWindow 确定，两窗口订单量可比\","
                    + "\"evidenceRefs\":[\"" + refs + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertEquals(3, run.evidenceChain().size());
        assertTrue(run.finalAnswer().contains("baselineWindow"));
    }

    @Test
    void 编造证据被拒_反馈重想后收敛() {
        AgentHarness h = harness(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("paymentSuccessRate", "2026-08-01~2026-08-07");
            }
            boolean hasFeedback = texts.stream().anyMatch(t -> t.contains("禁止编造证据"));
            if (!hasFeedback) {
                return answerJson("支付成功率下降至0.2", "ev_fake");
            }
            return answerJson("支付成功率为0.5", ev);
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么支付成功率下降了");

        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertTrue(run.finalAnswer().contains("0.5"));
    }

    @Test
    void 顽固编造证据_EVIDENCE_INVALID部分终止() {
        AgentHarness h = harness(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("contentInteraction", "2026-08-01~2026-08-07");
            }
            return answerJson("互动量骤降", "ev_fake");
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么内容互动下降了");

        assertEquals(RunStatus.PARTIAL, run.status());
        assertEquals(TerminationReason.EVIDENCE_INVALID, run.terminationReason());
        assertTrue(run.finalAnswer().contains("已收集证据"));
    }

    @Test
    void 非授权工具_策略耗尽终止() {
        AgentHarness h = harness(texts -> toolCallJson("dropDatabase", "2026-08-01~2026-08-07"),
                AgentBudget.defaults());

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.PARTIAL, run.status());
        assertEquals(TerminationReason.POLICY_EXHAUSTED, run.terminationReason());
    }

    @Test
    void 同参数死循环_循环检测终止() {
        AgentHarness h = harness(texts -> toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07"),
                AgentBudget.defaults());

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.PARTIAL, run.status());
        assertEquals(TerminationReason.LOOP_REPEATED_CALL, run.terminationReason());
    }

    @Test
    void 步骤预算耗尽_部分终止() {
        AtomicInteger n = new AtomicInteger(0);
        AgentHarness h = harness(texts -> toolCallJson("queryOrderVolume",
                "2026-08-0" + (1 + n.incrementAndGet()) + "~2026-08-14"), new AgentBudget(3, 100_000, 100));

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.PARTIAL, run.status());
        assertEquals(TerminationReason.BUDGET_STEPS, run.terminationReason());
        assertTrue(run.finalAnswer().contains("已用步骤 3/3"));
    }

    @Test
    void 模型不可用_明确降级FAILED() {
        AgentHarness h = harness(texts -> "THROW model down", AgentBudget.defaults());

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals(TerminationReason.MODEL_UNAVAILABLE, run.terminationReason());
        assertTrue(run.finalAnswer().contains("暂不可用"));
    }

    @Test
    void 工具异常_如实回填ERROR不抛出_可被证据引用() {
        MetricToolAccess broken = new MetricToolAccess() {
            @Override
            public String queryOrderVolume(String window) {
                throw new RuntimeException("数据库连接失败");
            }

            @Override
            public String paymentSuccessRate(String window) {
                return "rate=0.5";
            }

            @Override
            public String contentInteraction(String window) {
                return "interaction=48";
            }

            @Override
            public String baselineWindow(String window) {
                return "baseline.window";
            }
        };
        AgentHarness h = new AgentHarness(new FakeDecisionModel(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("工具不可用，无法获取下单量", ev);
        }), broken, MAPPER, AgentBudget.defaults(), 0.002, 2);

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertTrue(run.finalAnswer().contains("不可用"));
        assertEquals(true, run.registry().get(run.evidenceChain().entries().get(0).evidenceId())
                .orElseThrow().result().startsWith("ERROR"));
    }

    @Test
    void 成本预算耗尽_部分终止() {
        // 单价 1000000/1k tokens：第一步即超 maxCost=1 → 第二步 checkBeforeStep 触发 BUDGET_COST
        AgentHarness h = new AgentHarness(new FakeDecisionModel(texts ->
                        toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07")),
                new FakeMetricTools(), MAPPER, new AgentBudget(100, 100_000, 1.0), 1_000_000, 2);

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.PARTIAL, run.status());
        assertEquals(TerminationReason.BUDGET_COST, run.terminationReason());
    }

    @Test
    void 格式纠正_非JSON输出反馈重想后收敛() {
        AgentHarness h = harness(texts -> {
            boolean sawMalformed = texts.stream().anyMatch(t -> t.contains("不是合法 JSON"));
            if (!sawMalformed) {
                return "这不是 JSON，我说说我的想法……";
            }
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("下单量为61", ev);
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么订单量下降了");

        assertEquals(RunStatus.SUCCEEDED, run.status());
    }

    // ---- AgentDecisionCodec 单元 ----

    @Test
    void codec_剥离markdown围栏并解析() {
        AgentDecisionCodec codec = new AgentDecisionCodec(MAPPER);
        AgentDecision d = codec.parse("```json\n{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"x\"}\n```");
        assertNotNull(d);
        assertEquals("TOOL_CALL", d.action());
        assertEquals("queryOrderVolume", d.tool());
        assertEquals("2026-08-01~2026-08-07", d.args().get("window"));
    }

    @Test
    void codec_非法输出返回null() {
        AgentDecisionCodec codec = new AgentDecisionCodec(MAPPER);
        assertNull(codec.parse("随便说点什么"));
        assertNull(codec.parse(""));
        assertNull(codec.parse(null));
    }

    @Test
    void codec_未知action结构可解析_语义由Harness拒绝() {
        AgentDecisionCodec codec = new AgentDecisionCodec(MAPPER);
        AgentDecision d = codec.parse("{\"action\":\"BOGUS\"}不完整的json");
        assertNotNull(d);
        assertEquals("BOGUS", d.action());
    }

    @Test
    void codec_前后杂文本提取首个JSON对象() {
        AgentDecisionCodec codec = new AgentDecisionCodec(MAPPER);
        AgentDecision d = codec.parse("好的，我来查。{\"action\":\"TOOL_CALL\",\"tool\":\"paymentSuccessRate\","
                + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"}} 请稍等。");
        assertNotNull(d);
        assertEquals("paymentSuccessRate", d.tool());
    }
}
