package com.myxhs.ai.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.TerminationReason;
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
    void 断言_notRegex检测PII() {
        EvalAsserter a = new EvalAsserter();
        var run = new com.myxhs.ai.app.service.agent.harness.AgentRun("r", "q",
                com.myxhs.ai.app.service.agent.harness.AgentBudget.defaults());
        run.terminate(com.myxhs.ai.app.service.agent.harness.TerminationReason.COMPLETED,
                "用户手机号是 13812345678");
        EvalCase c = EvalCase.fromYaml(Map.of("id", "pii", "query", "q",
                "notRegex", List.of("1[3-9]\\d{9}")));
        List<String> fails = a.checkHard(c, run);
        assertEquals(1, fails.size(), "应检测手机号: " + fails);
        assertTrue(fails.get(0).contains("禁用模式"), fails.toString());
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
    void 数字一致性_精度与时间戳不误报() {
        EvalAsserter a = new EvalAsserter();
        // 工具返回 67.0/0.0，答案正常舍入为整数 → 数值语义一致（旧逻辑字符串匹配误报）
        var run = new AgentRun("r", "q", AgentBudget.defaults());
        run.registry().register("httpErrors", Map.of(), "{\"status\":\"ok\",\"metric\":\"service.http_errors\","
                + "\"asOf\":\"2026-08-15T07:37:53.403179046Z\",\"total5xx\":67.0,\"hours\":24,"
                + "\"byUri\":[{\"uri\":\"/actuator/health\",\"rate5xxPerSec\":58.0},"
                + "{\"uri\":\"UNKNOWN\",\"rate5xxPerSec\":7.0},{\"uri\":\"/api/home/feed\",\"rate5xxPerSec\":2.0}]}");
        run.terminate(TerminationReason.COMPLETED,
                "最近24小时5xx总数为67次，约58次集中在/actuator/health，7次UNKNOWN，/api/home/feed仅2次。"
                        + "观测时间2026-08-15 07:42:53。");
        assertTrue(a.checkNumberConsistency(run).isEmpty(), "舍入/时间戳数字不应误报: "
                + a.checkNumberConsistency(run));
        // 8-15 日期表达不参与比对（业务数字 46/7 保留）
        assertEquals(java.util.Set.of("46", "7"), a.extractNonPercentNumbers("基线窗口订单量46，当前7（集中在8-15）"));
        // 无秒时间（08:06）与年份（2025年）同样剥除
        assertEquals(java.util.Set.of(), a.extractNonPercentNumbers("根据当前观测（2026-08-15T08:06Z），2025年1月无数据"));
        // "2025-01 区间" 不得残留 "20"（\d{1,2}-\d{1,2} 从 4 位年份尾巴截取）
        assertEquals(java.util.Set.of(), a.extractNonPercentNumbers("2025-01 区间无数据，asOf 2026-08-15"));
        // 32 位 hex traceId/请求 ID 非业务数字（M14 抽样回归）
        assertEquals(java.util.Set.of("3"), a.extractNonPercentNumbers(
                "请求 abcdef0123456789abcdef0123456789 有 3 处错误"));
        assertTrue(a.checkNumberConsistency(traceIdRun("abcdef0123456789abcdef0123456789")).isEmpty(),
                "traceId 不应误报幻觉");
        // 中文括号的裸证据 ID 引用（模型输出形态）不参与比对
        assertEquals(java.util.Set.of("15"), a.extractNonPercentNumbers("发布事件15条（ev_c3d657ef1bd3）"));
        // 推导值（65.1-21.1=44）在证据量级窗口内不报幻觉；数量级编造仍检出
        var run3 = new AgentRun("r3", "q", AgentBudget.defaults());
        run3.registry().register("httpErrors", Map.of(), "{\"total5xx\":67.1,\"byUri\":[{\"uri\":\"health\",\"rate5xxPerSec\":65.1},{\"uri\":\"order\",\"rate5xxPerSec\":21.1}]}");
        run3.terminate(TerminationReason.COMPLETED, "网关约44/s的5xx，总量67.1/s");
        assertTrue(a.checkNumberConsistency(run3).isEmpty(), "推导值不应误报: " + a.checkNumberConsistency(run3));
        // 真实不一致仍须检出
        var run2 = new AgentRun("r2", "q", AgentBudget.defaults());
        run2.registry().register("queryOrderVolume", Map.of(), "{\"metric\":\"order.query_volume\",\"value\":9.0}");
        run2.terminate(TerminationReason.COMPLETED, "订单量为10000单");
        assertEquals(java.util.Set.of("10000"), a.checkNumberConsistency(run2),
                "真实数字编造必须检出");
    }

    /** traceId 场景：答案引用请求 ID（32hex），证据为日志检索结果 */
    private static AgentRun traceIdRun(String traceId) {
        var run = new AgentRun("r_trace", "q", AgentBudget.defaults());
        run.registry().register("logSearch", Map.of(),
                "{\"status\":\"ok\",\"tool\":\"log.search\",\"matches\":1,\"lines\":[\"2026-08-15T10:00:00Z ERROR request "
                        + traceId + " timeout\"]}");
        run.terminate(TerminationReason.COMPLETED,
                "请求 " + traceId + " 在订单服务超时，日志 1 条匹配");
        return run;
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
