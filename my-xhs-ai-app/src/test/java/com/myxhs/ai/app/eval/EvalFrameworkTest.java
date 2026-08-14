package com.myxhs.ai.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.ObsToolAccess;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测框架测试（fake 模型确定性）：
 *  - 断言执行（硬/软）
 *  - 数字一致性（百分比跳过/证据比对/不一致标记）
 *  - Runner 报告与指标
 */
class EvalFrameworkTest {

    private static final Pattern EV = Pattern.compile("证据 id=(ev_\\w+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class FakeModel implements ChatModel {
        private final String answerConclusion;
        private final boolean divergentWindows;

        FakeModel(String answerConclusion) {
            this(answerConclusion, false);
        }

        FakeModel(String answerConclusion, boolean divergentWindows) {
            this.answerConclusion = answerConclusion;
            this.divergentWindows = divergentWindows;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String all = request.messages().stream().map(EvalFrameworkTest::text).reduce("", String::concat);
            Matcher m = EV.matcher(all);
            String ev = m.find() ? m.group(1) : null;
            String json;
            if (divergentWindows) {
                // 真发散：永不收敛，每次换窗口查（预算/循环检测兜底）；%02d 保证窗口始终合法
                long calls = all.split("工具 queryOrderVolume 结果").length - 1;
                String day = String.format("%02d", 1 + calls);
                json = "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                        + "\"args\":{\"window\":\"2026-08-" + day + "~2026-08-07\"},\"reasoning\":\"换窗口\"}";
            } else if (ev == null) {
                json = "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                        + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"查\"}";
            } else {
                json = "{\"action\":\"ANSWER\",\"conclusion\":\"" + answerConclusion + "\","
                        + "\"evidenceRefs\":[\"" + ev + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(5, 5)).modelName("fake").build())
                    .build();
        }
    }

    private static String text(ChatMessage m) {
        if (m instanceof AiMessage a) {
            return a.text();
        }
        if (m instanceof UserMessage u) {
            return u.singleText();
        }
        if (m instanceof SystemMessage s) {
            return s.text();
        }
        return "";
    }

    private static final class FakeTools implements MetricToolAccess {
        @Override
        public String queryOrderVolume(String window) {
            return "{\"value\":61,\"window\":\"" + window + "\"}";
        }

        @Override
        public String paymentSuccessRate(String window) {
            return "{\"value\":0.5,\"success\":4,\"fail\":4}";
        }

        @Override
        public String contentInteraction(String window) {
            return "{\"total\":48,\"exposure\":16}";
        }

        @Override
        public String baselineWindow(String window) {
            return "{\"baseline\":\"2026-07-25~2026-07-31\"}";
        }

        @Override
        public String funnelConversion(String window) {
            return "{\"browse\":2,\"cartAdd\":1,\"order\":3}";
        }

        @Override
        public String paymentFailures(String window) {
            return "{\"totalFailures\":2}";
        }

        @Override
        public String notePublishEvents(String window) {
            return "{\"totalPublishes\":4}";
        }
    }

    private static final class FakeObs implements ObsToolAccess {
        @Override
        public String httpErrors(String service, String hours) {
            return "{\"total5xx\":9,\"noiseScanRoutes\":9}";
        }

        @Override
        public String httpLatency(String service, String hours) {
            return "{\"p95\":0.8}";
        }

        @Override
        public String mqConsumerLag(String group) {
            return "{\"totalLag\":0}";
        }

        @Override
        public String mqDlqBacklog(String consumerGroup) {
            return "{\"totalDlqBacklog\":-283}";
        }

        @Override
        public String mysqlReplicationLag() {
            return "{\"secondsBehindMaster\":0}";
        }

        @Override
        public String mysqlDeadlocks() {
            return "{\"deadlockTotal\":2,\"deadlockNewEvents\":0}";
        }
    }

    private AgentHarness harness() {
        return new AgentHarness(new FakeModel("当前窗口下单量为61"), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
    }

    @Test
    void 工具误用分析_发散检测() throws Exception {
        // 模型反复查同工具不同窗口（发散）：5 次 queryOrderVolume 不同窗口
        AgentHarness h = new AgentHarness(new FakeModel("下单量为61", true), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");

        Map<String, Object> report = new EvalRunner(h).run(List.of(
                EvalCase.fromYaml(Map.of("id", "div", "query", "为什么订单量下降了",
                        "statusIn", List.of("SUCCEEDED", "PARTIAL"), "minEvidence", 1))));

        Map<?, ?> result = (Map<?, ?>) ((List<?>) report.get("results")).get(0);
        Map<?, ?> usage = (Map<?, ?>) result.get("toolUsage");
        assertEquals(true, usage.get("divergent"), "同工具反复换窗口应标记发散: " + usage);
        assertTrue(((Number) usage.get("totalCalls")).intValue() >= 5, "应累计多次调用: " + usage);

        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertEquals(100.0, ((Number) summary.get("divergenceRate")).doubleValue());
        assertTrue(((Number) summary.get("avgTokensPerRun")).doubleValue() > 0, "应统计 token");
        assertTrue(((Number) summary.get("avgDurationMs")).doubleValue() >= 0, "应统计耗时");
    }

    @Test
    void 工具误用分析_正常run不标记发散() {
        Map<String, Object> report = new EvalRunner(harness()).run(List.of(
                EvalCase.fromYaml(Map.of("id", "ok2", "query", "为什么订单量下降了",
                        "statusIn", List.of("SUCCEEDED"), "minEvidence", 1))));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) report.get("results")).get(0);
        Map<?, ?> usage = (Map<?, ?>) result.get("toolUsage");
        assertEquals(false, usage.get("divergent"), "正常 1 次调用不应标记: " + usage);
        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertEquals(0.0, ((Number) summary.get("divergenceRate")).doubleValue());
    }

    @Test
    void 加载YAML评测集() {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases.yaml");
        assertTrue(cases.size() >= 15, "smoke 集应 ≥15 条: " + cases.size());
        EvalCase c = cases.get(0);
        assertEquals("a1_order_decline_funnel", c.id());
        assertTrue(!c.statusIn().isEmpty());
    }

    @Test
    void 数字一致性_百分比跳过_证据比对() {
        EvalAsserter a = new EvalAsserter();
        assertEquals(java.util.Set.of("61"), a.extractNonPercentNumbers("下单量为61，环比上升41.9%"));
        assertEquals(java.util.Set.of("2", "1", "3"), a.extractNonPercentNumbers("浏览2加购1下单3"));
        assertEquals(java.util.Set.of("61"), a.extractNonPercentNumbers("[ev_8eb5f018f7fc] 下单量为61"));
        assertTrue(a.extractAllNumbers("{\"value\":61,\"success\":4}").containsAll(java.util.Set.of("61", "4")));
    }

    @Test
    void runner_硬断言通过_指标汇总() throws Exception {
        EvalCase c = EvalCase.fromYaml(Map.of(
                "id", "ok", "query", "为什么订单量下降了",
                "statusIn", List.of("SUCCEEDED"),
                "minEvidence", 1, "contains", List.of("证据链"),
                "numbersConsistent", true));
        EvalRunner runner = new EvalRunner(harness());
        Map<String, Object> report = runner.run(List.of(c));


        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertEquals(1, ((Number) summary.get("total")).intValue());
        System.out.println("[eval-debug] answer=[" + ((Map<?,?>) ((List<?>) report.get("results")).get(0)).get("id") + "]");
        assertEquals(100.0, ((Number) summary.get("passRate")).doubleValue());
        assertEquals(0.0, ((Number) summary.get("hallucinationRate")).doubleValue());

        List<?> results = (List<?>) report.get("results");
        Map<?, ?> r = (Map<?, ?>) results.get(0);
        assertEquals(true, r.get("pass"));
        assertEquals(0, ((List<?>) r.get("hardFails")).size());
        assertEquals(0, ((List<?>) r.get("unmatchedNumbers")).size());
    }

    @Test
    void runner_数字编造_标记幻觉嫌疑() throws Exception {
        // 模型答案引用正确证据 id 但结论编造数字 99999（不在工具结果中）
        AgentHarness h = new AgentHarness(new FakeModel("下单量为99999"), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");

        EvalCase c = EvalCase.fromYaml(Map.of(
                "id", "halluc", "query", "为什么订单量下降了",
                "statusIn", List.of("SUCCEEDED"),
                "minEvidence", 1, "numbersConsistent", true));
        Map<String, Object> report = new EvalRunner(h).run(List.of(c));

        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertEquals(1, ((Number) summary.get("hallucinationSuspected")).intValue(), "编造数字应标记幻觉嫌疑");
        assertEquals(100.0, ((Number) summary.get("hallucinationRate")).doubleValue());
        assertEquals(0.0, ((Number) summary.get("passRate")).doubleValue());
    }
}
