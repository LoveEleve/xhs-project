package com.myxhs.ai.app.service.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(Intent.AGENT, router.classify("帮我排查下系统问题"));
    }

    @Test
    void requestTrace查询_走专用路由() {
        assertEquals(Intent.REQUEST_TRACE, router.classify("26f97b1880974a4f86eb5f0f0d950f9b"));
        assertEquals(Intent.REQUEST_TRACE, router.classify("帮我查一下这个 traceId 的日志"));
        assertEquals(Intent.REQUEST_TRACE, router.classify("requestId=abc123-def456 帮我看下调用链"));
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
        // "看看订单总额" 含领域词"订单"→ 确定性 METRIC；用纯模糊输入验证 LLM 兜底
        IntentRouter r = new IntentRouter(new FakeLlm(Intent.METRIC_ORDER_VOLUME));
        assertEquals(Intent.METRIC_ORDER_VOLUME, r.classify("最近业务情况汇总"));
    }

    @Test
    void 无诊断信号_默认Greeting引导() {
        // 核心设计：诊断是封闭集，完全无诊断信号的输入默认引导直答（零成本），不白跑 Agent
        assertEquals(Intent.GREETING, router.classify("最近业务情况汇总"));
        assertEquals(Intent.GREETING, router.classify("随便聊聊"));
        assertEquals(Intent.GREETING, router.classify("哈哈哈"));
        assertEquals(Intent.GREETING, router.classify("今天真不错"));
    }

    @Test
    void 领域词_直接命中指标() {
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("订单"));
        assertEquals(Intent.METRIC_ORDER_VOLUME, router.classify("看看订单总额"));
        assertEquals(Intent.METRIC_PAYMENT_RATE, router.classify("支付"));
        assertEquals(Intent.METRIC_CONTENT_INTERACTION, router.classify("内容互动"));
        assertEquals(Intent.METRIC_CONTENT_INTERACTION, router.classify("内容"));
    }

    @Test
    void 空串不走LLM兜底() {
        // 空白输入直接 GREETING 引导，即使有 LLM 兜底也不触发（省成本）
        IntentRouter r = new IntentRouter(new FakeLlm(Intent.METRIC_ORDER_VOLUME));
        assertEquals(Intent.GREETING, r.classify(""));
        assertEquals(Intent.GREETING, r.classify("   "));
        assertEquals(Intent.GREETING, r.classify(null));
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
        // 语义区分（超范围 vs 问候）是 LLM 分类的职责（纯规则模式无信号统一默认引导）
        IntentRouter llmRouter = new IntentRouter(new FakeLlm(Intent.OUT_OF_SCOPE));
        assertEquals(Intent.OUT_OF_SCOPE, llmRouter.classify("今天会下雨吗"));
        assertEquals(Intent.OUT_OF_SCOPE, llmRouter.classify("帮我写一段代码"));
        assertEquals(Intent.OUT_OF_SCOPE, llmRouter.classify("最近有什么新闻"));
        assertEquals(Intent.OUT_OF_SCOPE, llmRouter.classify("讲个笑话"));
    }

    @Test
    void LLM分类_问候语义走Greeting() {
        IntentRouter llmRouter = new IntentRouter(new FakeLlm(Intent.GREETING));
        assertEquals(Intent.GREETING, llmRouter.classify("我爱你"));
        assertEquals(Intent.GREETING, llmRouter.classify("随便聊聊"));
        assertEquals(Intent.GREETING, llmRouter.classify("最近过得咋样"));
    }

    @Test
    void LLM不可用_降级默认引导() {
        // LLM 返回 null（不可用/失败）→ 语义层/默认引导（不误路由 AGENT）
        IntentRouter r = new IntentRouter(null, (LlmIntentClassifier) msg -> null);
        assertEquals(Intent.GREETING, r.classify("随便聊聊"));
        // 纯规则模式（LLM+语义都无）：无信号输入统一默认引导
        assertEquals(Intent.GREETING, router.classify("今天会下雨吗"));
    }

    @Test
    void 超范围含归因词_不误判() {
        // 归因优先：带"为什么/怎么/分析"的仍走 Agent（DECLINE 机制兜底正确拒答）
        assertEquals(Intent.AGENT, router.classify("今天天气怎么样"));   // "怎么"→Agent→模型 DECLINE
        assertEquals(Intent.AGENT, router.classify("为什么最近订单量异常"));
        assertEquals(Intent.AGENT, router.classify("帮我分析一下天气对订单的影响"));
    }

    /** 边界输入回归集（批量探针固化）：高频日常表达不进 Agent */
    @Test
    void 边界输入_高频日常表达不进Agent() {
        String[] greeting = {
                "你能帮我吗", "帮我看下", "哈哈", "今天心情不好", "我是谁", "hello world",
                "在吗帮我看看", "help", "你好你好", "谢谢老板", "测一下",
                "你是AI吗", "介绍一下你自己", "吃饭了吗", "周末去哪玩",
                "我爱你", "我想你", "你真棒", "你太厉害了", "么么哒", "喜欢你", "你真好",
                "随便聊聊", "哈哈哈",
        };
        String[] outOfScope = {"最近有什么好电影", "写首诗", "今天吃什么", "什么是Nacos", "讲个恐怖故事"};
        String[] metric = {"订单量", "帮我查下订单量", "内容互动", "订单", "支付", "内容"};
        String[] agent = {
                "怎么弄", "天气怎么样", "订单量怎么样", "为什么订单量下降了",
                "订单量下降", "最近有 5xx 吗", "MySQL 主从延迟", "服务错误", "怎么查订单量",
                "支付为什么失败了", "内容互动异常", "帮忙看看这个报错", "帮我排查下系统问题",
                "帮我查一下 payment 服务的日志", "能帮我查日志吗", "26f97b1880974a4f86eb5f0f0d950f9b",
                "帮我查一下这个 traceId 的日志", "这个单号能帮我查一下吗",
        };
        for (String q : greeting) {
            assertEquals(Intent.GREETING, router.classify(q), "应 GREETING(默认引导): " + q);
        }
        IntentRouter llmRouter = new IntentRouter(new FakeLlm(Intent.OUT_OF_SCOPE));
        for (String q : outOfScope) {
            assertEquals(Intent.OUT_OF_SCOPE, llmRouter.classify(q), "应 OUT_OF_SCOPE(LLM 分类): " + q);
        }
        for (String q : metric) {
            assertTrue(router.classify(q) != Intent.AGENT && router.classify(q) != Intent.GREETING
                    && router.classify(q) != Intent.OUT_OF_SCOPE, "应确定性指标: " + q);
        }
        for (String q : agent) {
            Intent intent = router.classify(q);
            if (q.contains("traceId") || q.matches("[a-f0-9]{32}")) {
                assertEquals(Intent.REQUEST_TRACE, intent, "应 REQUEST_TRACE: " + q);
            } else {
                assertEquals(Intent.AGENT, intent, "应 AGENT: " + q);
            }
        }
    }
}
