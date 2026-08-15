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
    CANCELLED
}
