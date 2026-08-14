package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.store.JdbcRunStore;
import com.myxhs.ai.app.service.store.RunStore;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.ObsToolAccess;
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
import java.util.Map;
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

        @Override
        public String funnelConversion(String window) {
            return "funnel browse=10 cartAdd=5 order=3 pay=2 window=" + window;
        }

        @Override
        public String paymentFailures(String window) {
            return "failures total=2 byErrorCode=[CHANNEL_REJECT:2] window=" + window;
        }

        @Override
        public String notePublishEvents(String window) {
            return "publishes total=4 window=" + window;
        }
    }

    /** 假模型：按对话内容确定性返回决策 JSON；"THROW" 前缀=模拟模型故障 */
    private static final class FakeDecisionModel implements ChatModel {
        private final Function<List<String>, String> responder;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile List<String> lastTexts;

        FakeDecisionModel(Function<List<String>, String> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            lastTexts = request.messages().stream().map(AgentHarnessTest::messageText).toList();
            String json = responder.apply(lastTexts);
            if (json.startsWith("THROW")) {
                throw new RuntimeException("模型服务不可用");
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(10, 5)).modelName("fake").build())
                    .build();
        }

        List<String> lastTexts() {
            return lastTexts;
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

    /** 假观测工具：固定返回，含参数便于区分 */
    private static final class FakeObsTools implements ObsToolAccess {
        @Override
        public String httpErrors(String service, String hours) {
            return "5xx total=3 service=" + service + " hours=" + hours;
        }

        @Override
        public String httpLatency(String service, String hours) {
            return "p95=0.8 service=" + service + " hours=" + hours;
        }

        @Override
        public String mqConsumerLag(String group) {
            return "lag total=120 group=" + group;
        }

        @Override
        public String mqDlqBacklog(String consumerGroup) {
            return "dlq backlog=42 group=" + consumerGroup;
        }

        @Override
        public String mysqlReplicationLag() {
            return "replication lag=0s";
        }

        @Override
        public String mysqlDeadlocks() {
            return "deadlock total=2 new=0";
        }
    }

    private static AgentHarness harness(Function<List<String>, String> responder, AgentBudget budget) {
        return new AgentHarness(new FakeDecisionModel(responder), new FakeMetricTools(), new FakeObsTools(),
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
    void 观测工具_5xx归因走L2工具() {
        AgentHarness h = harness(texts -> {
            long toolResults = texts.stream().filter(t -> t.contains("证据 id=")).count();
            if (toolResults == 0) {
                return "{\"action\":\"TOOL_CALL\",\"tool\":\"httpErrors\","
                        + "\"args\":{\"service\":\"my-xhs-gateway\",\"hours\":\"6\"},\"reasoning\":\"查 5xx\"}";
            }
            String ev = lastEvId(texts);
            return answerJson("gateway 近 6 小时有 5xx 错误", ev);
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么最近有 5xx");

        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertTrue(run.finalAnswer().contains("5xx 错误"));
        var ev = run.evidenceChain().entries().get(0);
        assertEquals("httpErrors", ev.tool());
        assertTrue(run.registry().get(ev.evidenceId()).orElseThrow().result().contains("my-xhs-gateway"));
    }

    @Test
    void 观测工具_hours非法被策略拒绝() {
        AgentHarness h = harness(texts -> toolCallJson("httpErrors", "6"), AgentBudget.defaults());
        // 用 PolicyGuard 直接验证 hours 校验
        PolicyDecision d = new PolicyGuard().evaluate("httpErrors", Map.of("service", "my-xhs-gateway", "hours", "0"));
        assertEquals(false, d.allowed());
        PolicyDecision ok = new PolicyGuard().evaluate("httpErrors", Map.of("service", "my-xhs-gateway", "hours", "6"));
        assertEquals(true, ok.allowed());
    }

    @Test
    void MQ积压归因_走L2工具() {
        AgentHarness h = harness(texts -> {
            long toolResults = texts.stream().filter(t -> t.contains("证据 id=")).count();
            if (toolResults == 0) {
                return "{\"action\":\"TOOL_CALL\",\"tool\":\"mqConsumerLag\","
                        + "\"args\":{\"group\":\"cart-sync-consumer-group\"},\"reasoning\":\"查积压\"}";
            }
            String ev = lastEvId(texts);
            return answerJson("cart-sync 消费组积压 120", ev);
        }, AgentBudget.defaults());

        AgentRun run = h.run("为什么 MQ 有消费积压");

        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertTrue(run.finalAnswer().contains("积压 120"));
        assertEquals("mqConsumerLag", run.evidenceChain().entries().get(0).tool());
    }

    @Test
    void MQ组名注入_被策略拒绝() {
        PolicyDecision d = new PolicyGuard().evaluate("mqConsumerLag",
                Map.of("group", "a\";drop;"));
        assertEquals(false, d.allowed());
        PolicyDecision ok = new PolicyGuard().evaluate("mqConsumerLag",
                Map.of("group", "cart-sync-consumer-group"));
        assertEquals(true, ok.allowed());
    }

    @Test
    void 落库集成_run和step持久化() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:harnessstore;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_run (run_id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(64),"
                + " session_id VARCHAR(64), query TEXT NOT NULL, status VARCHAR(16) NOT NULL,"
                + " termination_reason VARCHAR(32), budget_json TEXT, versions_json TEXT, tokens_in BIGINT DEFAULT 0,"
                + " tokens_out BIGINT DEFAULT 0, cost_est DOUBLE DEFAULT 0, started_at DATETIME(3), ended_at DATETIME(3))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_step (id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(32),"
                + " step_no INT, state VARCHAR(24), decision_json TEXT, tool_result MEDIUMTEXT,"
                + " evidence_ids VARCHAR(512), messages_snapshot MEDIUMTEXT, created_at DATETIME(3))");
        RunStore store = new JdbcRunStore(jdbc, MAPPER);
        AgentHarness h = new AgentHarness(new FakeDecisionModel(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("下单量为61", ev);
        }), new FakeMetricTools(), new FakeObsTools(), MAPPER, AgentBudget.defaults(), 0.002, 2, store);

        AgentRun run = h.run("为什么订单量下降了");

        var rec = store.loadRun(run.runId()).orElseThrow();
        assertEquals("SUCCEEDED", rec.status());
        assertEquals("COMPLETED", rec.terminationReason());
        assertTrue(rec.versionsJson().contains("deepseek-v4-flash"), rec.versionsJson());
        assertTrue(rec.tokensIn() > 0, "应累计 token: " + rec.tokensIn());

        var steps = store.loadSteps(run.runId());
        assertTrue(steps.size() >= 3, "应有 THINK/TOOL/ANSWER 步骤: " + steps.size());
        assertEquals("TOOL", steps.stream().filter(s -> "TOOL".equals(s.state())).findFirst().orElseThrow().state());
        var cp = store.lastCheckpoint(run.runId()).orElseThrow();
        assertTrue(cp.messagesSnapshot().contains("用户问题"), "checkpoint 应含对话快照");
    }

    @Test
    void 事件流_完整序列推送() {
        AgentHarness h = harness(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("下单量为61", ev);
        }, AgentBudget.defaults());

        java.util.List<HarnessEvent> events = new java.util.ArrayList<>();
        h.run("为什么订单量下降了", AgentBudget.defaults(), events::add);

        java.util.List<String> types = events.stream().map(HarnessEvent::type).toList();
        assertEquals(java.util.List.of("RUN_STARTED", "THINK", "TOOL", "THINK", "ANSWER", "COMPLETED"), types);
        HarnessEvent tool = events.stream().filter(e -> "TOOL".equals(e.type())).findFirst().orElseThrow();
        assertEquals("queryOrderVolume", tool.tool());
        assertEquals("2026-08-01~2026-08-07", tool.window());
        assertEquals(1, tool.evidenceRefs().size());
        HarnessEvent done = events.get(events.size() - 1);
        assertEquals("COMPLETED", done.type());
        assertEquals("COMPLETED", done.terminationReason());
        assertTrue(done.message().contains("下单量为61"));
    }

    @Test
    void 事件流_partial终止推送终态() {
        AgentHarness h = harness(texts -> toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07"),
                AgentBudget.defaults());

        java.util.List<HarnessEvent> events = new java.util.ArrayList<>();
        h.run("为什么订单量下降了", AgentBudget.defaults(), events::add);

        HarnessEvent last = events.get(events.size() - 1);
        assertEquals("PARTIAL", last.type());
        assertEquals(TerminationReason.LOOP_REPEATED_CALL.name(), last.terminationReason());
    }

    @Test
    void 事件流_模型故障推送FAILED() {
        AgentHarness h = harness(texts -> "THROW model down", AgentBudget.defaults());

        java.util.List<HarnessEvent> events = new java.util.ArrayList<>();
        h.run("为什么订单量下降了", AgentBudget.defaults(), events::add);

        HarnessEvent last = events.get(events.size() - 1);
        assertEquals("FAILED", last.type());
        assertEquals(TerminationReason.MODEL_UNAVAILABLE.name(), last.terminationReason());
    }

    @Test
    void 注入确定性当前窗口_显式指定优先() {
        FakeDecisionModel model = new FakeDecisionModel(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("ok", ev);
        });
        AgentHarness h = new AgentHarness(model, new FakeMetricTools(), new FakeObsTools(), MAPPER,
                AgentBudget.defaults(), 0.002, 2);

        h.run("为什么 2026-08-10 到 2026-08-13 订单量下降了");

        String injected = model.lastTexts().stream()
                .filter(t -> t.contains("当前窗口已确定 ="))
                .findFirst().orElseThrow();
        assertTrue(injected.contains("2026-08-10~2026-08-13"), "应注入用户显式窗口: " + injected);
    }

    @Test
    void 注入确定性当前窗口_无指定取最近7天() {
        FakeDecisionModel model = new FakeDecisionModel(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("ok", ev);
        });
        AgentHarness h = new AgentHarness(model, new FakeMetricTools(), new FakeObsTools(), MAPPER,
                AgentBudget.defaults(), 0.002, 2);

        h.run("为什么订单量下降了");

        String injected = model.lastTexts().stream()
                .filter(t -> t.contains("当前窗口已确定 ="))
                .findFirst().orElseThrow();
        String expected = java.time.LocalDate.now(com.myxhs.ai.app.service.QueryWindowExtractor.zone())
                .minusDays(6) + "~" + java.time.LocalDate.now(com.myxhs.ai.app.service.QueryWindowExtractor.zone());
        assertTrue(injected.contains(expected), "应注入最近 7 天窗口: " + injected);
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

            @Override
            public String funnelConversion(String window) {
                return "funnel";
            }

            @Override
            public String paymentFailures(String window) {
                return "failures";
            }

            @Override
            public String notePublishEvents(String window) {
                return "publishes";
            }
        };
        AgentHarness h = new AgentHarness(new FakeDecisionModel(texts -> {
            String ev = lastEvId(texts);
            if (ev == null) {
                return toolCallJson("queryOrderVolume", "2026-08-01~2026-08-07");
            }
            return answerJson("工具不可用，无法获取下单量", ev);
        }), broken, new FakeObsTools(), MAPPER, AgentBudget.defaults(), 0.002, 2);

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
                new FakeMetricTools(), new FakeObsTools(), MAPPER, new AgentBudget(100, 100_000, 1.0), 1_000_000, 2);

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
