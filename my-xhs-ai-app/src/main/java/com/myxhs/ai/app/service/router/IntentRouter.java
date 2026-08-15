package com.myxhs.ai.app.service.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 意图路由（三阶分层，M8-4 语义路由重构）：
 *  - L0 规则层（零成本确定性）：诊断侧封闭集（指标词/归因词）→ 指标直取 / AGENT
 *  - L1 语义层（embedding few-shot）：种子示例余弦分类（问候/超范围/模糊诊断的语义区分，
 *    不维护闲聊词表——"我爱你""随便聊聊"靠语义即达）
 *  - L2 LLM 兜底（可选）：语义仍模糊时用 LLM 分类
 *  - L3 默认：GREETING 引导直答（诊断问题是封闭集已全部前置拦截，无信号输入零成本引导）
 * 降级链：embedding 不可用/失败 → 精简词表兜底 → 默认引导。
 * 原则（PLAN §2）：固定查询→确定性工具（数字可重复）；归因→受限 AGENT；不靠模型自觉。
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

    /** 超范围话题拒答内容（RunManager/AiQueryController 共用） */
    public static final String OUT_OF_SCOPE_ANSWER = """
            我是 my-xhs 运营诊断助手，能力范围是电商运营/运维指标诊断（订单、支付、内容互动、
            服务错误/延迟、MQ 积压、MySQL 主从等），且结论基于真实指标数据、带证据链。
            这个问题不在我的能力范围内，无法回答。
            可以问我，例如：为什么订单量下降了？最近支付成功率为什么异常？为什么有服务 5xx？""";

    /** L0 指标词（诊断侧封闭集，确定性直取） */
    private static final List<Pattern> ORDER_VOLUME_PATTERNS = List.of(
            compile("订单量"), compile("下单量"), compile("订单数"), compile("下单数"),
            compile("订单总量"), compile("订单总数"), compile("订单"), compile("单量"));
    private static final List<Pattern> PAYMENT_RATE_PATTERNS = List.of(
            compile("支付成功率"), compile("支付成功"), compile("付款成功率"),
            compile("支付失败率"), compile("支付"));
    private static final List<Pattern> CONTENT_INTERACTION_PATTERNS = List.of(
            compile("互动量"), compile("互动数"), compile("点赞"), compile("收藏"),
            compile("评论数"), compile("分享数"), compile("曝光量"), compile("互动"), compile("内容"));

    /** L0 归因词（诊断侧封闭集：领域词+归因动词，穷举完整且值得；闲聊侧不穷举） */
    private static final List<Pattern> INVESTIGATION_PATTERNS = List.of(
            compile("为什么"), compile("为何"), compile("原因"), compile("怎么"),
            compile("如何"), compile("分析"), compile("诊断"), compile("归因"),
            compile("下降"), compile("降低"), compile("异常"), compile("波动"),
            compile("故障"), compile("错误"), compile("延迟"), compile("慢"),
            compile("超时"), compile("失败"), compile("积压"), compile("挂了"),
            compile("问题"), compile("5xx"), compile("排查"), compile("卡顿"),
            compile("崩溃"), compile("宕"), compile("报错"), compile("日志"),
            compile("traceid"), compile("请求id"), compile("请求号"), compile("单号"));

    /** traceId/请求链路标识：32 位 hex 是查日志/链路的强信号（贴 ID 进来=想查它） */
    private static final java.util.regex.Pattern TRACE_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-f0-9]{32}$");

    /** 降级兜底词表（仅 embedding 不可用时生效；语义层正常时以下表达靠相似度即可识别） */
    private static final List<Pattern> FALLBACK_GREETING_PATTERNS = List.of(
            compile("你好"), compile("您好"), compile("在吗"), compile("谢谢"),
            compile("哈哈"), compile("测试"), compile("help"), compile("hello"), compile("hi"),
            compile("帮我"), compile("吃饭"), compile("心情"), compile("再见"), compile("你是谁"));
    private static final List<Pattern> FALLBACK_OUT_OF_SCOPE_PATTERNS = List.of(
            compile("天气"), compile("下雨"), compile("新闻"), compile("代码"), compile("笑话"),
            compile("电影"), compile("股票"), compile("翻译"), compile("什么是"), compile("诗"),
            compile("故事"), compile("吃什么"), compile("旅游"), compile("音乐"), compile("体育"),
            compile("游戏"), compile("数学"), compile("英语"), compile("考试"));

    private final SemanticIntentClassifier semanticClassifier;
    private final LlmIntentClassifier llmClassifier;

    /** 纯规则（测试用/无语义无 LLM 兜底） */
    public IntentRouter() {
        this(null, null);
    }

    public IntentRouter(LlmIntentClassifier llmClassifier) {
        this(null, llmClassifier);
    }

    /** 三阶路由（L0 规则 → L1 语义 → L2 LLM → L3 默认引导）；semantic/llm 均可空（降级） */
    public IntentRouter(SemanticIntentClassifier semanticClassifier, LlmIntentClassifier llmClassifier) {
        this.semanticClassifier = semanticClassifier;
        this.llmClassifier = llmClassifier;
    }

    public Intent classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Intent.GREETING; // 空/纯空白：引导直答（零成本）
        }
        String text = userMessage.toLowerCase(Locale.ROOT);

        // L0 归因/分析优先：为什么订单量下降 → Agent（不是固定查询，也不走 LLM/语义兜底）
        if (matches(INVESTIGATION_PATTERNS, text)) {
            return Intent.AGENT;
        }
        // traceId 强信号：32 位 hex（贴 ID 进来 = 想查日志/链路）
        if (TRACE_ID_PATTERN.matcher(userMessage.trim()).matches()) {
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

        // L1 语义层：问候/超范围/模糊诊断的语义区分（种子 few-shot，无词表）
        if (semanticClassifier != null) {
            Intent semantic = semanticClassifier.classify(userMessage);
            if (semantic != null) {
                return semantic;
            }
        }

        // 降级：embedding 不可用时精简词表兜底
        if (matches(FALLBACK_OUT_OF_SCOPE_PATTERNS, text)) {
            return Intent.OUT_OF_SCOPE;
        }
        if (matches(FALLBACK_GREETING_PATTERNS, text)) {
            return Intent.GREETING;
        }

        // L2 LLM 兜底（可选）
        if (llmClassifier != null) {
            return llmClassifier.classify(userMessage);
        }

        // L3 默认引导：无诊断信号（诊断侧封闭集已全部前置拦截），零成本直答
        return Intent.GREETING;
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
