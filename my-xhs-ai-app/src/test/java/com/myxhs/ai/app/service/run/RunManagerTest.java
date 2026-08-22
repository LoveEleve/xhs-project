package com.myxhs.ai.app.service.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
import com.myxhs.ai.app.service.agent.harness.HarnessEventType;
import com.myxhs.ai.app.service.knowledge.CodeSearchService;
import com.myxhs.ai.app.service.knowledge.KnowledgeAnswerComposer;
import com.myxhs.ai.app.service.knowledge.KnowledgeCardLoader;
import com.myxhs.ai.app.service.knowledge.KnowledgeQuestionClassifier;
import com.myxhs.ai.app.service.knowledge.KnowledgeRoutingService;
import com.myxhs.ai.app.service.trace.TraceDiagnosisService;
import com.myxhs.ai.app.service.trace.TraceSearchResult;
import com.myxhs.ai.tools.LogSearchAccess;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RunManager 测试（fake 模型，验证异步提交/事件缓冲/完成检测）。
 */
class RunManagerTest {

    private static final Pattern EV = Pattern.compile("证据 id=(ev_\\w+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class FakeModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            String all = request.messages().stream().map(RunManagerTest::text).reduce("", String::concat);
            Matcher m = EV.matcher(all);
            String ev = m.find() ? m.group(1) : null;
            String json = ev == null
                    ? "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                    + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"查\"}"
                    : "{\"action\":\"ANSWER\",\"conclusion\":\"下单量61\",\"evidenceRefs\":[\"" + ev
                    + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
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
            return "volume=61";
        }

        @Override
        public String paymentSuccessRate(String window) {
            return "rate=0.5";
        }

        @Override
        public String contentInteraction(String window) {
            return "total=48";
        }

        @Override
        public String baselineWindow(String window) {
            return "baseline";
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
    }

    private static class FakeLogSearch implements LogSearchAccess {
        public java.util.List<String> services() {
            return List.of("my-xhs-gateway", "my-xhs-order", "my-xhs-payment");
        }

        @Override
        public String searchLog(String service, String keyword, String tailLines) {
            if ("my-xhs-gateway".equals(service) || "my-xhs-order".equals(service)) {
                return "{\"status\":\"ok\",\"tool\":\"log.search\",\"service\":\"" + service
                        + "\",\"keyword\":\"" + keyword + "\",\"scannedLines\":2000,\"matches\":2,\"lines\":[\"trace hit\"]}";
            }
            return "{\"status\":\"ok\",\"tool\":\"log.search\",\"service\":\"" + service
                    + "\",\"keyword\":\"" + keyword + "\",\"scannedLines\":2000,\"matches\":0,\"lines\":[]}";
        }
    }

    private static final class FakeObs implements ObsToolAccess {
        @Override
        public String httpErrors(String service, String hours) {
            return "errors";
        }

        @Override
        public String httpLatency(String service, String hours) {
            return "latency";
        }

        @Override
        public String mqConsumerLag(String group) {
            return "lag";
        }

        @Override
        public String mqDlqBacklog(String consumerGroup) {
            return "dlq";
        }

        @Override
        public String mysqlReplicationLag() {
            return "repl";
        }

        @Override
        public String mysqlDeadlocks() {
            return "deadlock";
        }
    }

    @Test
    void 异步提交_事件缓冲_完成检测() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");

        // 立即返回（可能已完成——fake 模型快）；轮询完成
        e.future().get(10, TimeUnit.SECONDS);
        assertTrue(mgr.isDone(e.runId()));
        assertEquals("SUCCEEDED", e.future().join().status().name());

        // 事件缓冲：应有 RUN_STARTED..COMPLETED
        List<HarnessEvent> events = new java.util.ArrayList<>();
        HarnessEvent ev;
        while ((ev = e.events().poll()) != null) {
            events.add(ev);
        }
        assertEquals(HarnessEventType.RUN_STARTED, events.get(0).type());
        assertEquals(HarnessEventType.COMPLETED, events.get(events.size() - 1).type());
        assertTrue(events.stream().anyMatch(x -> HarnessEventType.TOOL.equals(x.type())), "应含 TOOL 事件");
    }

    @Test
    void 取消_run终止为CANCELLED() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");
        // fake 模型很快完成；取消窗口小——直接提交后立即取消（若已完成则取消返回 false，跳过）
        boolean cancelled = mgr.cancel(e.runId());
        e.future().get(10, TimeUnit.SECONDS);
        AgentRun run = e.future().join();
        if (cancelled) {
            assertEquals("CANCELLED", run.status().name());
        } else {
            assertEquals("SUCCEEDED", run.status().name()); // 太快完成，取消未生效
        }
    }

    @Test
    void 取消已完成run_返回false() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);
        RunManager.RunEntry e = mgr.submit("q", "u1");
        e.future().get(10, TimeUnit.SECONDS);
        assertEquals(false, mgr.cancel(e.runId()));
    }

    @Test
    void 并发订阅_第二个被拒绝() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");
        e.future().get(10, TimeUnit.SECONDS);

        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        boolean first = mgr.streamTo(e.runId(), x -> {}, done::countDown);
        assertTrue(first, "第一个订阅应成功");
        // 第一个仍在消费（done 未触发）时，第二个应被拒绝
        boolean second = mgr.streamTo(e.runId(), x -> {}, () -> {});
        assertEquals(false, second, "并发订阅应被拒绝（单消费者）");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        // 完成后可再次订阅（标志复位）
        boolean third = mgr.streamTo(e.runId(), x -> {}, () -> {});
        assertTrue(third, "完成复位后可再订阅");
    }

    @Test
    void streamTo_补发缓冲事件并完成() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");
        e.future().get(10, TimeUnit.SECONDS); // 完成后再订阅 → 全部缓冲补发

        List<HarnessEvent> received = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mgr.streamTo(e.runId(), received::add, done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS), "流应完成");
        assertTrue(received.size() >= 4, "应收到全部事件: " + received.size());
        assertEquals(HarnessEventType.COMPLETED, received.get(received.size() - 1).type());
    }

    @Test
    void 对外runId与事件视图一致_契约回归() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");
        e.future().get(10, TimeUnit.SECONDS);

        // 提交返回的 runId 必须等于 run 视图/全部事件的 runId（M8-4 契约修复）
        AgentRun run = e.future().join();
        assertEquals(run.runId(), e.runId(), "视图 runId 应与提交返回一致");
        List<HarnessEvent> events = new java.util.ArrayList<>();
        HarnessEvent ev;
        while ((ev = e.events().poll()) != null) {
            events.add(ev);
        }
        assertTrue(events.size() > 0, "应有事件");
        assertTrue(events.stream().allMatch(x -> e.runId().equals(x.runId())),
                "所有事件 runId 应与提交返回一致");
    }

    @Test
    void 客户端断开后_可重新订阅() throws Exception {
        // 阻塞模型：首次 THINK 挂起，构造"执行中"的 run（泵不会自行退出）；之后委托 FakeModel 正常完成
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch modelCalled = new java.util.concurrent.CountDownLatch(1);
        ChatModel fake = new FakeModel();
        ChatModel blocking = new ChatModel() {
            private final java.util.concurrent.atomic.AtomicInteger calls =
                    new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public ChatResponse chat(ChatRequest request) {
                if (calls.incrementAndGet() == 1) {
                    modelCalled.countDown();
                    try {
                        block.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return fake.chat(request);
            }
        };
        AgentHarness harness = new AgentHarness(blocking, new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");
        assertTrue(modelCalled.await(5, TimeUnit.SECONDS), "run 应已开始执行");

        // 客户端订阅后中途断开（刷新/断网）→ 标志必须释放，新订阅不再 409
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        assertTrue(mgr.streamTo(e.runId(), x -> {}, released::countDown), "第一个订阅应成功");
        mgr.cancelStream(e.runId());
        assertTrue(released.await(5, TimeUnit.SECONDS), "断开后泵应退出并释放标志");
        assertTrue(mgr.streamTo(e.runId(), x -> {}, () -> {}), "断开后应可重新订阅（M8-4 修复）");

        block.countDown();
        e.future().get(10, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", e.future().join().status().name());
    }

    @Test
    void 问候语_零成本直答不调工具() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null);

        RunManager.RunEntry e = mgr.submit("你好", "u1");
        e.future().get(10, TimeUnit.SECONDS);

        AgentRun run = e.future().join();
        assertEquals("SUCCEEDED", run.status().name());
        assertEquals(com.myxhs.ai.app.service.router.IntentRouter.GREETING_ANSWER, run.finalAnswer());
        assertEquals(0, run.steps().size(), "问候不应产生任何步骤（未调模型/工具）");
        // 事件流完整：RUN_STARTED → COMPLETED，无 THINK/TOOL
        List<HarnessEvent> events = new java.util.ArrayList<>();
        HarnessEvent ev;
        while ((ev = e.events().poll()) != null) {
            events.add(ev);
        }
        assertEquals(2, events.size(), "仅 RUN_STARTED + COMPLETED 两个事件");
        assertEquals(HarnessEventType.RUN_STARTED, events.get(0).type());
        assertEquals(HarnessEventType.COMPLETED, events.get(1).type());
    }

    @Test
    void 超范围话题_零成本拒答不调工具() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness, null, null,
                new com.myxhs.ai.app.service.router.IntentRouter(null,
                        msg -> com.myxhs.ai.app.service.router.Intent.OUT_OF_SCOPE));

        RunManager.RunEntry e = mgr.submit("今天会下雨吗", "u1");
        e.future().get(10, TimeUnit.SECONDS);

        AgentRun run = e.future().join();
        assertEquals("SUCCEEDED", run.status().name());
        assertEquals(com.myxhs.ai.app.service.router.IntentRouter.OUT_OF_SCOPE_ANSWER, run.finalAnswer());
        assertEquals(0, run.steps().size(), "超范围话题不应产生任何步骤（未调模型/工具）");
        List<HarnessEvent> events = new java.util.ArrayList<>();
        HarnessEvent ev;
        while ((ev = e.events().poll()) != null) {
            events.add(ev);
        }
        assertEquals(2, events.size());
        assertEquals(HarnessEventType.COMPLETED, events.get(1).type());
    }

    @Test
    void codeStructure查询_优先走CodeSearchService而不是旧知识卡() {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        KnowledgeRoutingService knowledge = new KnowledgeRoutingService(new KnowledgeCardLoader(),
                new KnowledgeQuestionClassifier(), new KnowledgeAnswerComposer());
        RunManager mgr = new RunManager(harness, null, null, new com.myxhs.ai.app.service.router.IntentRouter(),
                null, knowledge, null, null, new CodeSearchService(), null, null, null);

        AgentRun run = mgr.submit("哪个类负责库存预扣？", "u1").future().join();

        assertEquals("SUCCEEDED", run.status().name());
        assertTrue(run.finalAnswer().startsWith("代码结构："), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("InventoryService"), run.finalAnswer());
    }

    @Test
    void requestTrace查询_走跨服务直答() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        TraceDiagnosisService traceService = new TraceDiagnosisService(traceId -> new TraceSearchResult(
                traceId, "remote-es", List.of(
                new TraceSearchResult.ServiceHit("my-xhs-gateway", "入口/聚合层", 2),
                new TraceSearchResult.ServiceHit("my-xhs-order", "交易链路", 2)),
                List.of("my-xhs-payment"), List.of(),
                List.of(new TraceSearchResult.TraceEvent("2026-08-21T10:00:00", "my-xhs-gateway", "INFO", "gateway enter")),
                "my-xhs-gateway", "my-xhs-order"), new com.myxhs.ai.app.service.trace.TraceServiceContextLoader(), new com.myxhs.ai.app.service.trace.TraceCallChainLoader(), new com.myxhs.ai.app.service.trace.TraceNavigationLoader(), new com.myxhs.ai.app.service.trace.TraceDiagnosisReviewer());
        RunManager mgr = new RunManager(harness, null, null, new com.myxhs.ai.app.service.router.IntentRouter(),
                null, null, new FakeLogSearch(), traceService, null, null, null, null);

        RunManager.RunEntry e = mgr.submit("26f97b1880974a4f86eb5f0f0d950f9b", "u1");
        e.future().get(10, TimeUnit.SECONDS);

        AgentRun run = e.future().join();
        assertEquals("SUCCEEDED", run.status().name());
        assertTrue(e.traceDiagnosis() != null, "应挂上结构化 traceDiagnosis");
        assertEquals("remote-es", e.traceDiagnosis().source());
        assertTrue(run.finalAnswer().contains("来源：remote-es"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("my-xhs-gateway"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("my-xhs-order"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("my-xhs-payment"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("入口服务：my-xhs-gateway"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("最后命中服务：my-xhs-order"), run.finalAnswer());
        assertEquals(0, run.steps().size(), "request trace 直答分支不应进入 Agent 循环");
    }

    @Test
    void requestTrace查询_单服务失败不影响其他命中() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        LogSearchAccess access = new FakeLogSearch() {
            @Override
            public String searchLog(String service, String keyword, String tailLines) {
                if ("my-xhs-order".equals(service)) {
                    throw new IllegalStateException("日志源不可用");
                }
                return super.searchLog(service, keyword, tailLines);
            }
        };
        TraceDiagnosisService traceService = new TraceDiagnosisService(traceId -> new TraceSearchResult(
                traceId, "local-log-fallback", List.of(
                new TraceSearchResult.ServiceHit("my-xhs-gateway", "入口/聚合层", 2)),
                List.of("my-xhs-payment"), List.of("my-xhs-order"),
                List.of(), "my-xhs-gateway", "my-xhs-gateway"), new com.myxhs.ai.app.service.trace.TraceServiceContextLoader(), new com.myxhs.ai.app.service.trace.TraceCallChainLoader(), new com.myxhs.ai.app.service.trace.TraceNavigationLoader(), new com.myxhs.ai.app.service.trace.TraceDiagnosisReviewer());
        RunManager mgr = new RunManager(harness, null, null, new com.myxhs.ai.app.service.router.IntentRouter(),
                null, null, access, traceService, null, null, null, null);

        RunManager.RunEntry e = mgr.submit("26f97b1880974a4f86eb5f0f0d950f9b", "u1");
        AgentRun run = e.future().get(10, TimeUnit.SECONDS);

        assertTrue(run.finalAnswer().contains("来源：local-log-fallback"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("my-xhs-gateway"), run.finalAnswer());
        assertTrue(run.finalAnswer().contains("失败服务：my-xhs-order"), run.finalAnswer());
    }
}
