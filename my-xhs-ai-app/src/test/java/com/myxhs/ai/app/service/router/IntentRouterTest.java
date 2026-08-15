package com.myxhs.ai.app.service.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IntentRouter 规则单元测试（纯逻辑，无 DB/模型）。
 * 含 LLM 兜底行为：用 fake 分类器验证"何时该走/不该走 LLM"。
 */
class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    /** fake LLM 分类器：命中即返回指定指标，否则 AGENT */
    private static final class FakeLlm implements LlmIntentClassifier {
        private final Intent intent;

        FakeLlm(Intent intent) {
            this.intent = intent;
        }

        @Override
        public Intent classify(String userMessage) {
            return intent;
        }
    }

    @Test
    void 订单量关键词_走确定性() {
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("查一下订单量"));
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("2026-08-01 到 2026-08-07 的下单量"));
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("今天订单总数是多少"));
    }

    @Test
    void 支付成功率关键词_走确定性() {
        assertEquals(Intent.METRIC_PAYMENT_RATE, router.classify("支付成功率"));
        assertEquals(Intent.METRIC_PAYMENT_RATE, router.classify("查一下支付成功情况"));
        assertEquals(Intent.METRIC_PAYMENT_RATE, router.classify("付款成功率是多少"));
    }

    @Test
    void 互动关键词_走确定性() {
        assertEquals(Intent.METRIC_CONTENT_INTERACTION, router.classify("2026-08-01 到 2026-08-07 的互动量"));
        assertEquals(Intent.METRIC_CONTENT_INTERACTION, router.classify("查一下点赞数"));
        assertEquals(Intent.METRIC_CONTENT_INTERACTION, router.classify("曝光量"));
    }

    @Test
    void 归因开放问题_走Agent() {
        assertEquals(Intent.AGENT, router.classify("为什么订单量下降了"));
        assertEquals(Intent.AGENT, router.classify("为什么支付成功率降低"));
        assertEquals(Intent.AGENT, router.classify("帮我分析一下最近业务情况"));
        assertEquals(Intent.AGENT, router.classify("随便聊聊今天的情况"));
        assertEquals(Intent.AGENT, router.classify(null));
    }

    @Test
    void 同时含两个指标词_保守走Agent() {
        assertEquals(Intent.AGENT, router.classify("订单量和支付成功率都下降了，为什么"));
    }

    // ---- LLM 兜底行为 ----

    @Test
    void 规则命中时不走LLM() {
        // "查订单量" 规则确定性命中 → 即使 LLM 返回错误也不受影响
        IntentRouter r = new IntentRouter(new FakeLlm(Intent.METRIC_PAYMENT_RATE));
        assertEquals(Intent.METRIC_ORDER_VOLUME, r.classify("查一下订单量"));
    }

    @Test
    void 归因词不走LLM() {
        // "为什么订单量下降" 归因 → AGENT，LLM 兜底不被调用
        IntentRouter r = new IntentRouter(new FakeLlm(Intent.METRIC_ORDER_VOLUME));
        assertEquals(Intent.AGENT, r.classify("为什么订单量下降了"));
    }

    @Test
    void 规则模糊有LLM兜底时按LLM路由() {
        // "看看订单总额" 无规则命中（模糊）→ 走 LLM → METRIC_ORDER_VOLUME
        IntentRouter r = new IntentRouter(new FakeLlm(Intent.METRIC_ORDER_VOLUME));
        assertEquals(Intent.METRIC_ORDER_VOLUME, r.classify("看看订单总额"));
    }

    @Test
    void 规则模糊无LLM兜底时保守Agent() {
        assertEquals(Intent.AGENT, router.classify("看看订单总额"));
    }

    @Test
    void 空串不走LLM兜底() {
        // 空白输入直接 AGENT，即使有 LLM 兜底也不触发（省成本）
        IntentRouter r = new IntentRouter(new FakeLlm(Intent.METRIC_ORDER_VOLUME));
        assertEquals(Intent.AGENT, r.classify(""));
        assertEquals(Intent.AGENT, r.classify("   "));
        assertEquals(Intent.AGENT, r.classify(null));
    }

    @Test
    void 问候语_走Greeting直答() {
        assertEquals(Intent.GREETING, router.classify("你好"));
        assertEquals(Intent.GREETING, router.classify("您好，在吗"));
        assertEquals(Intent.GREETING, router.classify("hi"));
        assertEquals(Intent.GREETING, router.classify("hello"));
        assertEquals(Intent.GREETING, router.classify("你是谁"));
        assertEquals(Intent.GREETING, router.classify("谢谢"));
        assertEquals(Intent.GREETING, router.classify("测试"));
    }

    @Test
    void 问候含归因或指标词_不误判Greeting() {
        // 归因/指标词优先于问候：带诊断目标的"你好"仍走正确路径
        assertEquals(Intent.AGENT, router.classify("你好，为什么订单量下降了"));
        assertEquals(Intent.AGENT, router.classify("您好，帮忙分析下"));
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("你好，帮我查下订单量"));
    }

    @Test
    void 超范围话题_走拒答直答() {
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("今天会下雨吗"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("帮我写一段代码"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("最近有什么新闻"));
        assertEquals(Intent.OUT_OF_SCOPE, router.classify("讲个笑话"));
    }

    @Test
    void 超范围含归因词_不误判() {
        // 归因优先：带"为什么/怎么/分析"的仍走 Agent（DECLINE 机制兜底正确拒答）
        assertEquals(Intent.AGENT, router.classify("今天天气怎么样"));   // "怎么"→Agent→模型 DECLINE
        assertEquals(Intent.AGENT, router.classify("为什么最近订单量异常"));
        assertEquals(Intent.AGENT, router.classify("帮我分析一下天气对订单的影响"));
    }
}
