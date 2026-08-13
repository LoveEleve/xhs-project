package com.myxhs.ai.skeleton;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 契约测试（无 TEAMO_API_KEY 时自动跳过）。
 * 验证：chat / 结构化输出 / 工具调用循环 / 流式（对应去风险 A2/A3）。
 * 固化进评测集：`evals/` 的 smoke 层。
 */
@EnabledIfEnvironmentVariable(named = "TEAMO_API_KEY", matches = ".+")
class D1ContractTest {

    public record OrderVolumeReport(String metric, Long volume, String unit, String source) {
    }

    interface OrderAssistant {
        @SystemMessage("你是 my-xhs 运营诊断助手。查询订单/支付指标时使用工具，并基于工具结果回答。数字必须来自工具结果，不得编造。")
        String chat(@UserMessage String userMessage);
    }

    interface ReportExtractor {
        @UserMessage("从下面文本中提取订单量信息，按字段填入，不得编造/不得用默认值："
                + "metric=订单指标名，volume=订单量数值，unit=单位，source=来源。\n\n文本：{{it}}")
        OrderVolumeReport extract(@V("it") String text);
    }

    @Test
    void chat() {
        String reply = TeamoRouterConfig.chatModel().chat("一句话回答：你是谁？");
        assertNotNull(reply);
        assertTrue(reply.length() > 0);
    }

    @Test
    void structuredOutput() {
        ChatModel chat = TeamoRouterConfig.chatModel();
        ReportExtractor extractor = AiServices.builder(ReportExtractor.class)
                .chatModel(chat)
                .build();
        OrderVolumeReport report = extractor.extract(
                "订单量指标：metric=order.query_volume，value=12850 单，source=mock/order_daily_summary");
        assertNotNull(report);
        assertTrue(report.volume() != null && report.volume() > 0, "应提取到 volume>0");
        assertTrue(report.metric().contains("order"), "metric 应为 order.*");
    }

    @Test
    void toolCallingLoop() {
        ChatModel chat = TeamoRouterConfig.chatModel();
        OrderAssistant assistant = AiServices.builder(OrderAssistant.class)
                .chatModel(chat)
                .tools(new OrderTools())
                .build();
        String answer = assistant.chat("帮我查一下 2026-08-01~2026-08-07 的订单量，并用一句话总结。");
        assertNotNull(answer);
        assertTrue(answer.contains("12,850") || answer.contains("12850"),
                "Agent 应基于工具结果回答订单量，实际: " + answer);
    }

    @Test
    void streaming() throws InterruptedException {
        StreamingChatModel streaming = TeamoRouterConfig.streamingChatModel();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> acc = new AtomicReference<>("");
        AtomicReference<Throwable> err = new AtomicReference<>();
        streaming.chat("一句话介绍 my-xhs 电商平台。", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                acc.accumulateAndGet(partialResponse, String::concat);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                err.set(error);
                latch.countDown();
            }
        });
        assertTrue(latch.await(90, TimeUnit.SECONDS), "流式超时");
        assertTrue(err.get() == null, "流式错误: " + err.get());
        assertTrue(acc.get().length() > 0);
    }
}
