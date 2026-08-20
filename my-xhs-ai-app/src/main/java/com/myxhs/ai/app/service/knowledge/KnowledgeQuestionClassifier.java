package com.myxhs.ai.app.service.knowledge;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 第一版知识问答分类器：规则优先，不上 embedding。
 */
@Component
public class KnowledgeQuestionClassifier {

    public KnowledgeQuestionType classify(String q) {
        if (q == null) return null;
        String text = q.toLowerCase(Locale.ROOT);
        if (containsAny(text, "哪个类", "哪个核心类", "核心类", "哪个consumer", "哪个 consumer", "哪个job", "哪个 job", "哪个topic", "哪个 topic", "哪个feign", "哪个 feign", "代码里在哪", "哪一层负责", "主逻辑在哪", "负责库存预扣主逻辑", "负责支付主逻辑", "负责退款主逻辑")) {
            return KnowledgeQuestionType.CODE_STRUCTURE;
        }
        if (containsAny(text, "整体架构", "系统架构", "服务分层", "边界是什么", "为什么这样设计", "主链路怎么走", "为什么复杂", "为什么需要",
                "bff", "编排中心", "三级扣减", "事务消息", "本地消息表", "补偿任务", "支付链", "退款链", "关单", "补偿路径")) {
            return KnowledgeQuestionType.SYSTEM_KNOWLEDGE;
        }
        return null;
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }
}
