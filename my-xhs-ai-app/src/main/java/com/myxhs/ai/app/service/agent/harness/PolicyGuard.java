package com.myxhs.ai.app.service.agent.harness;

import com.myxhs.ai.tools.AccessLevel;
import com.myxhs.ai.tools.AgentToolNames;
import com.myxhs.ai.tools.ToolRegistry;
import com.myxhs.ai.tools.ToolSpec;

import java.util.Map;

/**
 * 策略守卫（设计 §2 VALIDATE + §5 HITL）：deny-by-default 工具 allowlist + 参数校验。
 * M12 注册表驱动：allowlist/参数规则/权限级全部读 ToolRegistry（单一事实源）——
 * 新增工具注册即生效，零改本类。
 *  - L1/L2（注册且可执行）→ ALLOW（参数经 spec.validator 校验）
 *  - L3 或未绑定执行器 → REQUIRES_APPROVAL（HITL 门，V1 恒不通过）
 *  - 未注册 → DENY
 * 工具名常量保留（引用点零改动），值委托 AgentToolNames 单一事实源。
 */
public class PolicyGuard {

    public static final String TOOL_ORDER_VOLUME = AgentToolNames.QUERY_ORDER_VOLUME;
    public static final String TOOL_PAYMENT_RATE = AgentToolNames.PAYMENT_SUCCESS_RATE;
    public static final String TOOL_CONTENT_INTERACTION = AgentToolNames.CONTENT_INTERACTION;
    public static final String TOOL_BASELINE_WINDOW = AgentToolNames.BASELINE_WINDOW;
    public static final String TOOL_HTTP_ERRORS = AgentToolNames.HTTP_ERRORS;
    public static final String TOOL_HTTP_LATENCY = AgentToolNames.HTTP_LATENCY;
    public static final String TOOL_MQ_LAG = AgentToolNames.MQ_CONSUMER_LAG;
    public static final String TOOL_MQ_DLQ = AgentToolNames.MQ_DLQ_BACKLOG;
    public static final String TOOL_MYSQL_REPLICA_LAG = AgentToolNames.MYSQL_REPLICA_LAG;
    public static final String TOOL_MYSQL_DEADLOCKS = AgentToolNames.MYSQL_DEADLOCKS;
    public static final String TOOL_FUNNEL = AgentToolNames.FUNNEL_CONVERSION;
    public static final String TOOL_PAY_FAILURES = AgentToolNames.PAYMENT_FAILURES;
    public static final String TOOL_NOTE_PUBLISH = AgentToolNames.NOTE_PUBLISH_EVENTS;
    public static final String TOOL_LOG_SEARCH = AgentToolNames.LOG_SEARCH;

    private final ToolRegistry registry;

    public PolicyGuard(ToolRegistry registry) {
        this.registry = registry;
    }

    /** 可用工具数（版本追溯用；只算 level!=L3 且有执行器） */
    public int allowedToolCount() {
        return registry.usableCount();
    }

    public PolicyDecision evaluate(String tool, Map<String, String> args) {
        return evaluate(tool, args, null);
    }

    /** M13：allowedTools 非空时按画像子集过滤（子集外 deny）；null=全量（单 Agent 兼容） */
    public PolicyDecision evaluate(String tool, Map<String, String> args, java.util.Set<String> allowedTools) {
        if (tool == null || tool.isBlank()) {
            return PolicyDecision.deny("tool 为空");
        }
        var specOpt = registry.get(tool);
        if (specOpt.isEmpty()) {
            return PolicyDecision.deny("非授权工具: " + tool + "（deny-by-default，仅允许注册工具）");
        }
        if (allowedTools != null && !allowedTools.contains(tool)) {
            return PolicyDecision.deny("工具 " + tool + " 不可用（当前 Agent 仅限本领域工具）");
        }
        ToolSpec spec = specOpt.get();
        // M11 修正：L3 且无执行器（预留动作）→ 直接 deny（未开放，审批无从执行，不挂起）
        if (spec.level() == AccessLevel.L3 && spec.invoker() == null) {
            return PolicyDecision.deny(tool + " 属 L3 高危动作且未开放执行（V1 不可用）");
        }
        if (spec.level() == AccessLevel.L3) {
            // M11：L3 可执行工具——先参数校验（非法直接拒绝，不浪费审批），再进审批门
            if (spec.validator() != null) {
                String invalid = spec.validator().apply(args);
                if (invalid != null) {
                    return PolicyDecision.deny(tool + " 参数非法: " + invalid);
                }
            }
            return PolicyDecision.requiresApproval(tool + " 属 L3 高危动作，需人工审批（HITL）");
        }
        if (spec.invoker() == null) {
            // 非 L3 但未绑执行器（装配遗漏）：安全方向拒绝，不误判审批
            return PolicyDecision.deny(tool + " 未绑定执行器（装配问题），拒绝执行");
        }
        if (spec.validator() != null) {
            String invalid = spec.validator().apply(args);
            if (invalid != null) {
                return PolicyDecision.deny(tool + " 参数非法: " + invalid);
            }
        }
        return PolicyDecision.allow();
    }
}
