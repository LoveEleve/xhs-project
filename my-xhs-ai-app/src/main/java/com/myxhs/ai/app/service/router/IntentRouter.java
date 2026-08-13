package com.myxhs.ai.app.service.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 意图路由（完整版）：规则优先，模糊时可选 LLM 兜底。
 * 原则（PLAN §2）：
 *  - 固定查询（订单量/支付成功率/内容互动）→ 确定性工具（不走 LLM）
 *  - 归因/分析（为什么/下降/原因…）→ **直接 AGENT**（多步调查），**不走 LLM 兜底**（防"为什么订单量下降"被误路由到指标）
 *  - 规则模糊（0 个 或 >1 个指标词命中）→ 配置开启 LLM 兜底时用 LLM 分类，否则保守 AGENT
 * 线程安全：无状态（llmClassifier 不可变）。
 */
public class IntentRouter {

    private static final List<Pattern> ORDER_VOLUME_PATTERNS = List.of(
            compile("订单量"), compile("下单量"), compile("订单数"),
            compile("下单数"), compile("订单总量"), compile("订单总数"));
    private static final List<Pattern> PAYMENT_RATE_PATTERNS = List.of(
            compile("支付成功率"), compile("支付成功"), compile("付款成功率"),
            compile("支付失败率"));
    private static final List<Pattern> CONTENT_INTERACTION_PATTERNS = List.of(
            compile("互动量"), compile("互动数"), compile("点赞"), compile("收藏"),
            compile("评论数"), compile("分享数"), compile("曝光量"));
    /** 归因/分析意图：命中即 AGENT（优先于指标关键词，也不走 LLM 兜底） */
    private static final List<Pattern> INVESTIGATION_PATTERNS = List.of(
            compile("为什么"), compile("为何"), compile("原因"), compile("怎么"),
            compile("如何"), compile("分析"), compile("诊断"), compile("归因"),
            compile("下降"), compile("降低"), compile("异常"), compile("波动"));

    private final LlmIntentClassifier llmClassifier;

    /** 纯规则（测试用/默认无 LLM 兜底） */
    public IntentRouter() {
        this(null);
    }

    public IntentRouter(LlmIntentClassifier llmClassifier) {
        this.llmClassifier = llmClassifier;
    }

    public Intent classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Intent.AGENT; // 空/纯空白：直接 AGENT，不触发 LLM 兜底（省成本）
        }
        String text = userMessage.toLowerCase(Locale.ROOT);

        // 归因/分析优先：为什么订单量下降 → Agent（不是固定查询，也不走 LLM 兜底）
        if (matches(INVESTIGATION_PATTERNS, text)) {
            return Intent.AGENT;
        }

        List<Intent> hits = new ArrayList<>(3);
        if (matches(ORDER_VOLUME_PATTERNS, text)) {
            hits.add(Intent.METRIC_ORDER_VOLUME);
        }
        if (matches(PAYMENT_RATE_PATTERNS, text)) {
            hits.add(Intent.METRIC_PAYMENT_RATE);
        }
        if (matches(CONTENT_INTERACTION_PATTERNS, text)) {
            hits.add(Intent.METRIC_CONTENT_INTERACTION);
        }

        if (hits.size() == 1) {
            return hits.get(0); // 确定性
        }
        // 0 个 或 >1 个指标命中 → 模糊：LLM 兜底（开启时）否则 AGENT
        if (llmClassifier != null) {
            return llmClassifier.classify(userMessage);
        }
        return Intent.AGENT;
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern compile(String keyword) {
        return Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
    }
}
