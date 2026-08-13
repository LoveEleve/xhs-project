package com.myxhs.ai.app.service.agent.harness;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 策略守卫（设计 §2 VALIDATE + §5 HITL）：deny-by-default 工具 allowlist + 参数校验。
 *  - L1 业务只读工具（MetricToolAccess 三件套）→ ALLOW
 *  - L3 高危动作（重启/重投/写，V1 未开放）→ REQUIRES_APPROVAL（HITL 门，V1 恒不通过）
 *  - 其余一切 → DENY
 * 参数校验：window 必须 yyyy-MM-dd~yyyy-MM-dd、start≤end、跨度≤31 天（口径与工具一致）。
 */
public class PolicyGuard {

    public static final String TOOL_ORDER_VOLUME = "queryOrderVolume";
    public static final String TOOL_PAYMENT_RATE = "paymentSuccessRate";
    public static final String TOOL_CONTENT_INTERACTION = "contentInteraction";

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            TOOL_ORDER_VOLUME, TOOL_PAYMENT_RATE, TOOL_CONTENT_INTERACTION);

    /** L3 高危动作（V1 一律人工审批；不在 allowlist，Agent 无法执行） */
    private static final Set<String> L3_TOOLS = Set.of("service.restart", "dlq.redeliver", "order.refund");

    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})~(\\d{4}-\\d{2}-\\d{2})");
    private static final long MAX_WINDOW_DAYS = 31;

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
        String invalid = validateWindow(args == null ? null : args.get("window"));
        if (invalid != null) {
            return PolicyDecision.deny(tool + " 参数非法: " + invalid);
        }
        return PolicyDecision.allow();
    }

    /** 返回错误消息，null=合法 */
    static String validateWindow(String window) {
        if (window == null || window.isBlank()) {
            return "window 必填（yyyy-MM-dd~yyyy-MM-dd）";
        }
        var m = WINDOW_PATTERN.matcher(window.trim());
        if (!m.matches()) {
            return "window 格式必须为 yyyy-MM-dd~yyyy-MM-dd";
        }
        var start = java.time.LocalDate.parse(m.group(1));
        var end = java.time.LocalDate.parse(m.group(2));
        if (start.isAfter(end)) {
            return "window 起始日期不能晚于结束日期";
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > MAX_WINDOW_DAYS - 1) {
            return "window 跨度不能超过 " + MAX_WINDOW_DAYS + " 天";
        }
        return null;
    }
}
