package com.myxhs.ai.app.service.agent.harness;

import com.myxhs.ai.tools.MetricWindow;

import java.util.Map;
import java.util.Set;

/**
 * 策略守卫（设计 §2 VALIDATE + §5 HITL）：deny-by-default 工具 allowlist + 参数校验。
 *  - L1 业务只读工具（MetricToolAccess 四件套，window 参数）→ ALLOW
 *  - L2 观测只读工具（ObsToolAccess 两件套，service+hours 参数，B3 面）→ ALLOW
 *  - L3 高危动作（重启/重投/写，V1 未开放）→ REQUIRES_APPROVAL（HITL 门，V1 恒不通过）
 *  - 其余一切 → DENY
 * 参数校验：window 规则单一事实源 = MetricWindow；hours 规则在本地（1~168 整数）。
 */
public class PolicyGuard {

    public static final String TOOL_ORDER_VOLUME = "queryOrderVolume";
    public static final String TOOL_PAYMENT_RATE = "paymentSuccessRate";
    public static final String TOOL_CONTENT_INTERACTION = "contentInteraction";
    public static final String TOOL_BASELINE_WINDOW = "baselineWindow";
    public static final String TOOL_HTTP_ERRORS = "httpErrors";
    public static final String TOOL_HTTP_LATENCY = "httpLatency";

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            TOOL_ORDER_VOLUME, TOOL_PAYMENT_RATE, TOOL_CONTENT_INTERACTION, TOOL_BASELINE_WINDOW,
            TOOL_HTTP_ERRORS, TOOL_HTTP_LATENCY);

    /** 需要 window 参数的工具（业务+基线） */
    private static final Set<String> WINDOW_TOOLS = Set.of(
            TOOL_ORDER_VOLUME, TOOL_PAYMENT_RATE, TOOL_CONTENT_INTERACTION, TOOL_BASELINE_WINDOW);

    /** 需要 hours 参数的工具（L2 观测） */
    private static final Set<String> HOURS_TOOLS = Set.of(TOOL_HTTP_ERRORS, TOOL_HTTP_LATENCY);

    /** L3 高危动作（V1 一律人工审批；不在 allowlist，Agent 无法执行） */
    private static final Set<String> L3_TOOLS = Set.of("service.restart", "dlq.redeliver", "order.refund");

    public PolicyDecision evaluate(String tool, Map<String, String> args) {
        if (tool == null || tool.isBlank()) {
            return PolicyDecision.deny("tool 为空");
        }
        if (L3_TOOLS.contains(tool)) {
            return PolicyDecision.requiresApproval(tool + " 属 L3 高危动作，V1 需人工审批（HITL）");
        }
        if (!ALLOWED_TOOLS.contains(tool)) {
            return PolicyDecision.deny("非授权工具: " + tool + "（deny-by-default，仅允许固定只读工具）");
        }
        if (WINDOW_TOOLS.contains(tool)) {
            String invalid = validateWindow(args == null ? null : args.get("window"));
            if (invalid != null) {
                return PolicyDecision.deny(tool + " 参数非法: " + invalid);
            }
        } else if (HOURS_TOOLS.contains(tool)) {
            String invalid = validateHours(args == null ? null : args.get("hours"));
            if (invalid != null) {
                return PolicyDecision.deny(tool + " 参数非法: " + invalid);
            }
        }
        return PolicyDecision.allow();
    }

    /** 返回错误消息，null=合法（规则单一事实源 MetricWindow） */
    static String validateWindow(String window) {
        try {
            MetricWindow.parse(window);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** hours 校验：1~168 整数 */
    static String validateHours(String hours) {
        if (hours == null || hours.isBlank()) {
            return "hours 必填（最近小时数 1~168）";
        }
        try {
            int h = Integer.parseInt(hours.trim());
            if (h < 1 || h > 168) {
                return "hours 必须在 1~168 之间";
            }
            return null;
        } catch (NumberFormatException e) {
            return "hours 必须为整数";
        }
    }
}
