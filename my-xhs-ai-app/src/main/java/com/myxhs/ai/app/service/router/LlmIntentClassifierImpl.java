package com.myxhs.ai.app.service.router;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LLM 意图分类器（主路径）：意图理解是 LLM 的职责，规则/种子只做确定性降级。
 * 覆盖全意图语义（AGENT/GREETING/OUT_OF_SCOPE/三个指标），返回枚举 + 一句理由。
 * 失败/超时 → null（调用方降级到语义层/默认引导），不保守回 AGENT（防"空跑调查"白花钱）。
 */
public class LlmIntentClassifierImpl implements LlmIntentClassifier {

    interface Classifier {
        @UserMessage("""
                你是 my-xhs 运营诊断助手的意图路由器。判断用户请求属于哪个意图，只输出一行：意图枚举 + 空格 + 简短理由。
                意图枚举（必须原样输出）：
                - METRIC_ORDER_VOLUME：查订单量/下单量/订单数（用户要数字，路径确定）
                - METRIC_PAYMENT_RATE：查支付成功率/支付数据（要数字）
                - METRIC_CONTENT_INTERACTION：查内容互动/点赞/收藏/评论/分享/曝光（要数字）
                - AGENT：运营/运维诊断归因类（为什么下降/异常/报错/查日志/查 traceId/排查问题等，需要多步调查）
                - GREETING：问候/闲聊/寒暄/情感表达/无明确目标（你好/谢谢/我爱你/随便聊聊/在吗）
                - OUT_OF_SCOPE：明显超出能力范围的日常话题（天气/新闻/代码/美食/股票/电影等）
                判定规则：诊断归因类（含查日志/traceId/排查）一律 AGENT；要数字的固定指标才是指标意图；
                其余无法归类的短消息默认 GREETING。
                用户请求：{{it}}""")
        String classify(@V("it") String message);
    }

    private final Classifier classifier;

    public LlmIntentClassifierImpl(ChatModel chatModel) {
        this.classifier = AiServices.builder(Classifier.class).chatModel(chatModel).build();
    }

    @Override
    public Intent classify(String userMessage) {
        try {
            String raw = classifier.classify(userMessage == null ? "" : userMessage);
            if (raw == null) {
                return null;
            }
            String token = raw.trim().split("[\\s，,。：:]+")[0].toUpperCase(java.util.Locale.ROOT);
            try {
                return Intent.valueOf(token);
            } catch (IllegalArgumentException e) {
                // 模型输出了非法枚举：按内容模糊匹配，仍不匹配返回 null（降级）
                String t = raw.toLowerCase(java.util.Locale.ROOT);
                if (t.contains("greeting") || t.contains("问候")) {
                    return Intent.GREETING;
                }
                if (t.contains("out_of_scope") || t.contains("超出")) {
                    return Intent.OUT_OF_SCOPE;
                }
                if (t.contains("agent") || t.contains("诊断") || t.contains("调查")) {
                    return Intent.AGENT;
                }
                return null;
            }
        } catch (Exception e) {
            return null; // LLM 不可用/超时：降级（不保守 AGENT，防空跑调查）
        }
    }
}
