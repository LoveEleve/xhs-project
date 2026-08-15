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
        if (tool == null || tool.isBlank()) {
            return PolicyDecision.deny("tool 为空");
        }
        var specOpt = registry.get(tool);
        if (specOpt.isEmpty()) {
            return PolicyDecision.deny("非授权工具: " + tool + "（deny-by-default，仅允许注册工具）");
        }
        ToolSpec spec = specOpt.get();
        if (spec.level() == AccessLevel.L3 || spec.invoker() == null) {
            return PolicyDecision.requiresApproval(
                    tool + " 属 L3 高危动作，V1 需人工审批（HITL）");
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
