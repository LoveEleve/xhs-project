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

import java.util.Map;

/**
 * 意图路由端点（D1）：固定指标 → 确定性工具直取（不走 LLM）；开放问题 → Agent。
 * 返回 intent + path(metric/agent) + result。
 */
@RestController
@RequestMapping("/api/ai")
public class AiQueryController {

    private static final Logger log = LoggerFactory.getLogger(AiQueryController.class);

    private final IntentRouter intentRouter;
    private final MetricToolAccess metricToolAccess;
    private final MetricAssistant metricAssistant;
    /** 直答落库（P2-2：/api/ai/query 的问候/超范围直答与 /api/runs 审计一致） */
    private final com.myxhs.ai.app.service.run.RunManager runManager;

    public AiQueryController(IntentRouter intentRouter,
                             MetricToolAccess metricToolAccess,
                             MetricAssistant metricAssistant,
                             com.myxhs.ai.app.service.run.RunManager runManager) {
        this.intentRouter = intentRouter;
        this.metricToolAccess = metricToolAccess;
        this.metricAssistant = metricAssistant;
        this.runManager = runManager;
    }

    @PostMapping("/query")
    public Map<String, String> query(@RequestBody Map<String, String> body,
                                     @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
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
                case GREETING:
                    result = directAnswer(message, body, "GREETING", IntentRouter.GREETING_ANSWER, headerUserId);
                    break;
                case OUT_OF_SCOPE:
                    result = directAnswer(message, body, "OUT_OF_SCOPE", IntentRouter.OUT_OF_SCOPE_ANSWER, headerUserId);
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

    /** 直答（问候/超范围）：与 /api/runs 一致走 RunManager 落库（可追溯），响应带 runId */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Map<String, String> directAnswer(String message, Map<String, String> body,
                                             String intent, String answer, String headerUserId) {
        // Gateway 集成（2026-08-16）：X-User-Id header 优先、body 兜底
        String userId = firstNonBlank(headerUserId, body.get("userId"), "anonymous");
        try {
            var entry = runManager.submit(message, userId);
            Map<String, String> m = new java.util.HashMap<>();
            m.put("intent", intent);
            m.put("path", "chat");
            m.put("result", answer);
            m.put("runId", entry.runId());
            return m;
        } catch (Exception e) {
            log.warn("[query] 直答落库失败（不影响应答）: err={}", e.getMessage());
            return Map.of("intent", intent, "path", "chat", "result", answer);
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
     * 时区固定 Asia/Shanghai。规则单一事实源 = QueryWindowExtractor（与 AgentHarness 共用）。
     */
    static String extractWindow(String message) {
        return com.myxhs.ai.app.service.QueryWindowExtractor.extract(message);
    }
}
