package com.myxhs.ai.app.service.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 意图路由（分层，主次正确）：
 *  - L0 确定性规则（极简，零成本）：只拦"必须确定性"的信号——
 *    指标词（数字可重复）、归因词（明确诊断信号，防模型误判/不靠模型自觉）、traceId 格式
 *  - L1 LLM 意图分类（主路径，默认开启）：其余全部交给模型理解
 *    （问候/超范围/模糊诊断/查日志/traceId 语义都是 LLM 的职责，不人工枚举）
 *  - L2 语义层（降级）：LLM 不可用/失败时的 embedding 兜底（种子仍保留）
 *  - L3 默认：GREETING 引导直答（零成本）
 * 原则：人写规则模拟"理解"是错误架构（开放集永远枚举不完）；LLM 负责理解，规则只保证确定性。
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

    /** L0 指标词（确定性直取：数字可重复原则，不靠模型） */
    private static final List<Pattern> ORDER_VOLUME_PATTERNS = List.of(
            compile("订单量"), compile("下单量"), compile("订单数"), compile("下单数"),
            compile("订单总量"), compile("订单总数"), compile("订单"), compile("单量"));
    private static final List<Pattern> PAYMENT_RATE_PATTERNS = List.of(
            compile("支付成功率"), compile("支付成功"), compile("付款成功率"),
            compile("支付失败率"), compile("支付"));
    private static final List<Pattern> CONTENT_INTERACTION_PATTERNS = List.of(
            compile("互动量"), compile("互动数"), compile("点赞"), compile("收藏"),
            compile("评论数"), compile("分享数"), compile("曝光量"), compile("互动"), compile("内容"));

    /** L0 归因词（明确诊断信号 → 直接 AGENT，不靠模型自觉） */
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
    private static final Pattern TRACE_ID_PATTERN =
            Pattern.compile("^[a-f0-9]{32}$");

    private final SemanticIntentClassifier semanticClassifier;
    private final LlmIntentClassifier llmClassifier;

    /** 纯规则（测试用/LLM 与语义均禁用） */
    public IntentRouter() {
        this(null, null);
    }

    public IntentRouter(LlmIntentClassifier llmClassifier) {
        this(null, llmClassifier);
    }

    /** 分层路由：L0 规则 → L1 LLM → L2 语义 → L3 默认引导；llm/semantic 均可空（逐级降级） */
    public IntentRouter(SemanticIntentClassifier semanticClassifier, LlmIntentClassifier llmClassifier) {
        this.semanticClassifier = semanticClassifier;
        this.llmClassifier = llmClassifier;
    }

    public Intent classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Intent.GREETING; // 空/纯空白：引导直答（零成本）
        }
        String text = userMessage.toLowerCase(Locale.ROOT);

        // L0 系统知识问答：先于通用 AGENT，避免“整体架构是什么”误走运行态调查
        if (containsAny(text, "整体架构", "系统架构", "服务分层", "主链路", "边界是什么", "为什么这样设计", "为什么复杂",
                "bff", "编排中心", "三级扣减", "事务消息", "本地消息表", "补偿任务", "支付链", "退款链", "关单", "补偿路径")) {
            return Intent.SYSTEM_KNOWLEDGE;
        }
        if (containsAny(text, "哪个类", "哪个核心类", "核心类", "哪个服务", "哪个模块", "哪个组件", "哪个消费者", "哪个consumer", "哪个 consumer", "哪个job", "哪个 job", "哪个topic", "哪个 topic", "哪个feign", "哪个 feign", "代码里在哪", "代码在哪", "源码在哪", "源码落点", "在哪里实现", "哪一层负责", "主逻辑在哪", "谁负责", "负责库存预扣主逻辑", "负责支付主逻辑", "负责退款主逻辑", "为什么优先看", "这个方法", "负责什么", "最近谁改过")) {
            return Intent.CODE_STRUCTURE;
        }

        if (looksLikeRequestTraceQuery(userMessage, text)) {
            return Intent.REQUEST_TRACE;
        }

        // L0 归因/分析优先：为什么订单量下降 → Agent（确定信号不走模型）
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
            return hits.get(0); // 确定性指标（数字可重复）
        }

        // L1 LLM 意图分类（主路径）：问候/超范围/模糊诊断等语义理解交给模型
        if (llmClassifier != null) {
            Intent llm = llmClassifier.classify(userMessage);
            if (llm != null) {
                return llm;
            }
        }

        // L2 语义层降级（LLM 不可用/失败）：embedding 种子
        if (semanticClassifier != null) {
            Intent semantic = semanticClassifier.classify(userMessage);
            if (semantic != null) {
                return semantic;
            }
        }

        // L3 默认引导：无信号且 LLM/语义不可用，零成本直答
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

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeRequestTraceQuery(String userMessage, String text) {
        String trimmed = userMessage == null ? "" : userMessage.trim();
        if (TRACE_ID_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return containsAny(text, "traceid", "requestid", "request id", "请求id", "请求号", "调用链", "请求流转", "链路追踪", "trace id");
    }
}
