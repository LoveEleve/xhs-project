package com.myxhs.ai.app.service.router;

/**
 * LLM 意图分类器（D4 方向，可选）。
 * 规则路径模糊（多意图/无命中）时兜底，输出一个 Metric Intent 或 AGENT。
 */
public interface LlmIntentClassifier {

    /** 返回具体意图；无法确定时返回 AGENT（保守） */
    Intent classify(String userMessage);
}
