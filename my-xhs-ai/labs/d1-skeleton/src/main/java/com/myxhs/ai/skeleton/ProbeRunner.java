package com.myxhs.ai.skeleton;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D1 骨架实证探针：验证 LangChain4j 1.0 + 火山 deepseek-v4-flash 的
 * ① chat ② 结构化输出 ③ 工具调用(多步循环=A2) ④ 流式。
 * 运行：需要环境变量 ARK_API_KEY。
 */
public class ProbeRunner {

    /** 结构化输出目标类型（framework 路径：AiServices 自动 JSON→POJO） */
    public record OrderVolumeReport(String metric, Long volume, String unit, String source) {
    }

    /** 工具调用 Agent 接口（AiServices 自动跑 model↔tool 多步循环） */
    interface OrderAssistant {
        @SystemMessage("你是 my-xhs 运营诊断助手。查询订单/支付指标时使用工具，并基于工具结果回答。"
                + "数字必须来自工具结果，不得编造。")
        String chat(@UserMessage String userMessage);
    }

    /** 结构化输出接口 */
    interface ReportExtractor {
        @UserMessage("从下面文本中提取订单量信息，按字段填入，不得编造/不得用默认值："
                + "metric=订单指标名，volume=订单量数值，unit=单位，source=来源。\n\n文本：{{it}}")
        OrderVolumeReport extract(@V("it") String text);
    }

    public static void main(String[] args) throws Exception {
        ChatModel chat = TeamoRouterConfig.chatModel();

        // ① chat
        System.out.println("===== ① Chat =====");
        String chatReply = chat.chat("一句话回答：你是谁？");
        System.out.println("回复: " + chatReply);
        check(chatReply != null && !chatReply.isBlank(), "chat");

        // ② 结构化输出
        System.out.println("\n===== ② 结构化输出 (AiServices→POJO) =====");
        ReportExtractor extractor = AiServices.builder(ReportExtractor.class)
                .chatModel(chat)
                .build();
        OrderVolumeReport report = extractor.extract(
                "订单量指标：metric=order.query_volume，value=12850 单，source=mock/order_daily_summary");
        System.out.println("结构化结果: " + report);
        check(report != null && report.volume() != null && report.volume() > 0, "structured-output");

        // ③ 工具调用（多步循环，A2 关键）
        System.out.println("\n===== ③ 工具调用 Agent 循环 (AiServices + @Tool) =====");
        OrderAssistant assistant = AiServices.builder(OrderAssistant.class)
                .chatModel(chat)
                .tools(new OrderTools())
                .build();
        String answer = assistant.chat("帮我查一下 2026-08-01~2026-08-07 的订单量，并用一句话总结。");
        System.out.println("Agent 回答: " + answer);
        check(answer != null && !answer.isBlank() && (answer.contains("12,850") || answer.contains("12850")), "tool-loop");

        // ④ 流式
        System.out.println("\n===== ④ 流式 (SSE 前置验证) =====");
        String streamed = streamOne("一句话介绍 my-xhs 电商平台。");
        System.out.println("流式结果: " + streamed);
        check(streamed != null && !streamed.isBlank(), "streaming");

        System.out.println("\n===== 结果汇总 =====");
        System.out.println("chat: " + results.get(0));
        System.out.println("structured-output: " + results.get(1));
        System.out.println("tool-loop: " + results.get(2));
        System.out.println("streaming: " + results.get(3));
        boolean allPass = results.stream().allMatch(Boolean.TRUE::equals);
        System.out.println(allPass ? "\n✅ 全部通过（A1/A2/A3 关键能力实证）" : "\n❌ 存在失败项，请检查上方输出");
    }

    private static final java.util.List<Boolean> results = new java.util.ArrayList<>();

    private static void check(boolean ok, String name) {
        results.add(ok);
        System.out.println("[" + name + "] " + (ok ? "✅ PASS" : "❌ FAIL"));
    }

    private static String streamOne(String prompt) throws InterruptedException {
        StreamingChatModel streaming = TeamoRouterConfig.streamingChatModel();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> acc = new AtomicReference<>("");
        AtomicReference<Throwable> err = new AtomicReference<>();
        streaming.chat(prompt, new StreamingChatResponseHandler() {
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
        if (!latch.await(90, TimeUnit.SECONDS)) {
            return null;
        }
        if (err.get() != null) {
            err.get().printStackTrace();
            return null;
        }
        return acc.get();
    }
}
