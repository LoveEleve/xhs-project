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

    /** 问候/闲聊直答内容（非诊断任务零成本应答；RunManager/AiQueryController 共用） */
    public static final String GREETING_ANSWER = """
            你好！我是 my-xhs 运营诊断助手，基于真实指标数据做多步归因调查，结论带证据链、可追溯。
            你可以这样问我：
            - 为什么订单量下降了？
            - 最近支付成功率为什么异常？
            - 内容互动量波动是什么原因？
            - 帮我分析最近的服务错误或延迟
            - 为什么 MQ 有消费积压？""";

    private static final List<Pattern> ORDER_VOLUME_PATTERNS = List.of(
            compile("订单量"), compile("下单量"), compile("订单数"),
            compile("下单数"), compile("订单总量"), compile("订单总数"));
    private static final List<Pattern> PAYMENT_RATE_PATTERNS = List.of(
            compile("支付成功率"), compile("支付成功"), compile("付款成功率"),
            compile("支付失败率"));
    private static final List<Pattern> CONTENT_INTERACTION_PATTERNS = List.of(
            compile("互动量"), compile("互动数"), compile("点赞"), compile("收藏"),
            compile("评论数"), compile("分享数"), compile("曝光量"), compile("互动"));
    /** 归因/分析意图：命中即 AGENT（优先于指标关键词，也不走 LLM 兜底） */
    private static final List<Pattern> INVESTIGATION_PATTERNS = List.of(
            compile("为什么"), compile("为何"), compile("原因"), compile("怎么"),
            compile("如何"), compile("分析"), compile("诊断"), compile("归因"),
            compile("下降"), compile("降低"), compile("异常"), compile("波动"));
    /** 问候/闲聊/无诊断目标：规则模糊时才判（归因/指标词已优先消耗），命中即 GREETING 不走 Agent */
    private static final List<Pattern> GREETING_PATTERNS = List.of(
            compile("你好"), compile("您好"), compile("嗨"), compile("哈喽"), compile("hello"),
            compile("hi"), compile("在吗"), compile("你是谁"), compile("我是谁"), compile("能干什么"),
            compile("可以做什么"), compile("帮助"), compile("帮我"), compile("帮忙"), compile("谢谢"),
            compile("再见"), compile("测试"), compile("测一下"), compile("试试"), compile("哈哈"),
            compile("心情"), compile("吃饭"), compile("去哪玩"), compile("介绍一下"), compile("你是ai"),
            compile("help"));

    /** 明显超范围话题（与诊断词几乎零重叠的通用闲聊域）：命中即 OUT_OF_SCOPE 直答拒答，零成本。
     *  判定在 GREETING 之前（"帮我写代码"按主体话题拒答而非引导）。 */
    private static final List<Pattern> OUT_OF_SCOPE_PATTERNS = List.of(
            compile("天气"), compile("气温"), compile("下雨"), compile("新闻"), compile("股票"),
            compile("汇率"), compile("翻译"), compile("写代码"), compile("代码"), compile("数学"),
            compile("美食"), compile("旅游"), compile("电影"), compile("音乐"), compile("体育"),
            compile("足球"), compile("篮球"), compile("游戏"), compile("恋爱"), compile("星座"),
            compile("算命"), compile("笑话"), compile("故事"), compile("诗"), compile("作诗"),
            compile("英语"), compile("学习"), compile("考试"), compile("招聘"), compile("工资"),
            compile("什么是"), compile("吃什么"));

    /** 超范围话题拒答内容（RunManager/AiQueryController 共用） */
    public static final String OUT_OF_SCOPE_ANSWER = """
            我是 my-xhs 运营诊断助手，能力范围是电商运营/运维指标诊断（订单、支付、内容互动、
            服务错误/延迟、MQ 积压、MySQL 主从等），且结论基于真实指标数据、带证据链。
            这个问题不在我的能力范围内，无法回答。
            可以问我，例如：为什么订单量下降了？最近支付成功率为什么异常？为什么有服务 5xx？""";

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
        // 0 个 或 >1 个指标命中 → 先超范围判定（主体话题明确无关，如"帮我写代码"），
        // 再问候判定（"帮我"类求助词），再 LLM 兜底，最后保守 AGENT
        if (matches(OUT_OF_SCOPE_PATTERNS, text)) {
            return Intent.OUT_OF_SCOPE;
        }
        if (matches(GREETING_PATTERNS, text)) {
            return Intent.GREETING;
        }
        // 模糊：LLM 兜底（开启时）否则 AGENT
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
