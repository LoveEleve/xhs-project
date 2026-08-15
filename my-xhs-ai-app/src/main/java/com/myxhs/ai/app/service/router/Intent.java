package com.myxhs.ai.app.service.router;

/**
 * 意图类型（D1 最小版）。
 * 原则（PLAN §2）：能确定查询的走确定性路径（工具直取，不走 LLM），需开放调查的才走 Agent。
 */
public enum Intent {
    /** 固定指标：订单量 → order.query_volume（确定性） */
    METRIC_ORDER_VOLUME,
    /** 固定指标：支付成功率 → payment.success_rate（确定性） */
    METRIC_PAYMENT_RATE,
    /** 固定指标：内容互动 → content.interaction（确定性） */
    METRIC_CONTENT_INTERACTION,
    /** 开放调查/其他 → Agent（多步归因） */
    AGENT,
    /** 问候/闲聊/无诊断目标 → 直接应答（零模型/工具成本，不进 Agent） */
    GREETING,
    /** 明显超范围话题（天气/新闻等）→ 直接拒答（零成本，不进 Agent） */
    OUT_OF_SCOPE
}
