package com.myxhs.ai.app.service.agent.harness;

/**
 * Harness 事件类型（契约类型化，替代散落的字符串字面量）。
 * SSE 推送时序列化为 name()（前端契约不变）。
 */
public enum HarnessEventType {
    RUN_STARTED,
    THINK,
    POLICY_DENIED,
    TOOL,
    ANSWER,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    /** M11 HITL：L3 工具挂起待审批（message 含 tool/args/说明） */
    WAITING_APPROVAL,
    /** M11 HITL：审批结果（approve→resume 继续 / reject→终态；前端可据此刷新） */
    APPROVAL_RESULT
}
