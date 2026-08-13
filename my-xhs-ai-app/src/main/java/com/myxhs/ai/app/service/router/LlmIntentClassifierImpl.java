package com.myxhs.ai.app.service.router;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LLM 意图分类器实现（AiServices 结构化输出 → Intent）。
 * 仅当配置 myxhs.ai.router.llm-fallback.enabled=true 时启用（默认关，规则路径保持确定性）。
 */
public class LlmIntentClassifierImpl implements LlmIntentClassifier {

    interface Classifier {
        @UserMessage("判断用户请求属于哪个指标意图。可选："
                + "METRIC_ORDER_VOLUME(查订单量/下单量/订单数)、METRIC_PAYMENT_RATE(查支付成功率)、"
                + "METRIC_CONTENT_INTERACTION(查内容互动/点赞/收藏/评论/分享/曝光)。"
                + "若无法确定属于上述固定指标，返回 AGENT。只返回一个枚举名。\n\n用户请求：{{it}}")
        Intent classify(@V("it") String message);
    }

    private final Classifier classifier;

    public LlmIntentClassifierImpl(ChatModel chatModel) {
        this.classifier = AiServices.builder(Classifier.class).chatModel(chatModel).build();
    }

    @Override
    public Intent classify(String userMessage) {
        try {
            Intent intent = classifier.classify(userMessage == null ? "" : userMessage);
            return intent == null ? Intent.AGENT : intent;
        } catch (Exception e) {
            return Intent.AGENT; // LLM 失败保守回退 Agent
        }
    }
}
