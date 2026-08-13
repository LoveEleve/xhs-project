package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.agent.MetricAssistant;
import com.myxhs.ai.app.service.router.Intent;
import com.myxhs.ai.app.service.router.IntentRouter;
import com.myxhs.ai.tools.MetricToolAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图路由端点（D1）：固定指标 → 确定性工具直取（不走 LLM）；开放问题 → Agent。
 * 返回 intent + path(metric/agent) + result。
 */
@RestController
@RequestMapping("/api/ai")
public class AiQueryController {

    private static final Logger log = LoggerFactory.getLogger(AiQueryController.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final IntentRouter intentRouter;
    private final MetricToolAccess metricToolAccess;
    private final MetricAssistant metricAssistant;

    public AiQueryController(IntentRouter intentRouter,
                             MetricToolAccess metricToolAccess,
                             MetricAssistant metricAssistant) {
        this.intentRouter = intentRouter;
        this.metricToolAccess = metricToolAccess;
        this.metricAssistant = metricAssistant;
    }

    @PostMapping("/query")
    public Map<String, String> query(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // D1 可观测：每请求生成 traceId 入 MDC（本线程工具/模型日志随 logback %X{traceId} 带上），响应返回便于溯源
        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        try {
            Intent intent = intentRouter.classify(message);
            Map<String, String> result;
            switch (intent) {
                case METRIC_ORDER_VOLUME:
                    result = metricResult("METRIC_ORDER_VOLUME", message, () ->
                            metricToolAccess.queryOrderVolume(extractWindow(message)));
                    break;
                case METRIC_PAYMENT_RATE:
                    result = metricResult("METRIC_PAYMENT_RATE", message, () ->
                            metricToolAccess.paymentSuccessRate(extractWindow(message)));
                    break;
                case METRIC_CONTENT_INTERACTION:
                    result = metricResult("METRIC_CONTENT_INTERACTION", message, () ->
                            metricToolAccess.contentInteraction(extractWindow(message)));
                    break;
                default:
                    result = agentResult(intent.name(), message);
                    break;
            }
            return withTrace(result, traceId);
        } finally {
            MDC.remove("traceId");
        }
    }

    /** agent 路径：模型调用包 try-catch，模型不可用时返回 error JSON（不 500，满足 Gate"明确降级"） */
    private Map<String, String> agentResult(String intent, String message) {
        try {
            return Map.of("intent", intent, "path", "agent", "result", metricAssistant.chat(message));
        } catch (Exception e) {
            log.warn("[query] agent 路径失败(模型不可用?): msg={}, err={}", message, e.getMessage());
            return Map.of("intent", intent, "path", "agent", "status", "error",
                    "error", "模型暂不可用，请稍后重试: " + String.valueOf(e.getMessage()));
        }
    }

    /** metric 路径：工具调用包 try-catch，失败返回 error JSON（不 500） */
    private Map<String, String> metricResult(String intent, String message, java.util.function.Supplier<String> call) {
        try {
            return Map.of("intent", intent, "path", "metric", "result", call.get());
        } catch (Exception e) {
            log.warn("[query] {} 失败: msg={}, err={}", intent, message, e.getMessage());
            return Map.of("intent", intent, "path", "metric", "status", "error",
                    "error", String.valueOf(e.getMessage()));
        }
    }

    private static Map<String, String> withTrace(Map<String, String> base, String traceId) {
        java.util.Map<String, String> m = new java.util.HashMap<>(base);
        m.put("traceId", traceId);
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 从消息提取时间窗 "yyyy-MM-dd~yyyy-MM-dd"（半开）。
     * 支持：两个日期（任意分隔）/ 单个日期（当日）/ 今天 / 昨天 / 本周 / 上周；无则默认最近 7 天。
     * 时区固定 Asia/Shanghai。
     */
    static String extractWindow(String message) {
        LocalDate today = LocalDate.now(ZONE);
        if (message == null) {
            return today.minusDays(6) + "~" + today;
        }
        Matcher m = DATE_PATTERN.matcher(message);
        if (m.find()) {
            String first = m.group(1);
            if (m.find()) {
                return first + "~" + m.group(1);
            }
            return first + "~" + first; // 单日期 → 当日
        }
        if (message.contains("上周")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return monday + "~" + monday.plusDays(6);
        }
        if (message.contains("本周")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return monday + "~" + today;
        }
        if (message.contains("昨天")) {
            return today.minusDays(1) + "~" + today.minusDays(1);
        }
        if (message.contains("今天")) {
            return today + "~" + today;
        }
        return today.minusDays(6) + "~" + today;
    }
}
