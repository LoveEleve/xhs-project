package com.myxhs.ai.app.service.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.HarnessEvent;
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
        RunManager mgr = new RunManager(harness);

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
        assertEquals("RUN_STARTED", events.get(0).type());
        assertEquals("COMPLETED", events.get(events.size() - 1).type());
        assertTrue(events.stream().anyMatch(x -> "TOOL".equals(x.type())), "应含 TOOL 事件");
    }

    @Test
    void streamTo_补发缓冲事件并完成() throws Exception {
        AgentHarness harness = new AgentHarness(new FakeModel(), new FakeTools(), new FakeObs(),
                MAPPER, AgentBudget.defaults(), 0.002, 2, null, "fake");
        RunManager mgr = new RunManager(harness);

        RunManager.RunEntry e = mgr.submit("为什么订单量下降了", "u1");
        e.future().get(10, TimeUnit.SECONDS); // 完成后再订阅 → 全部缓冲补发

        List<HarnessEvent> received = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mgr.streamTo(e.runId(), received::add, done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS), "流应完成");
        assertTrue(received.size() >= 4, "应收到全部事件: " + received.size());
        assertEquals("COMPLETED", received.get(received.size() - 1).type());
    }
}
