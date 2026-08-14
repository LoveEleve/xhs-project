package com.myxhs.ai.app.service.agent.harness;

/**
 * Harness 执行事件（设计 §8 SSE 流式推送的载荷，D5 异步化前 V1 定义）。
 * type：RUN_STARTED / THINK / POLICY_DENIED / TOOL / ANSWER / COMPLETED / PARTIAL / FAILED
 * 语义：
 *  - THINK：模型决策（tool/reasoning 可空，message=决策 JSON 文本）
 *  - TOOL：工具执行完成（tool/window/message=结果，evidenceRefs=[evidenceId]）
 *  - ANSWER：模型给出结论（message=结论）
 *  - 终态（COMPLETED/PARTIAL/FAILED）：terminationReason + message=finalAnswer
 */
public record HarnessEvent(
        String runId,
        String type,
        int stepNumber,
        String tool,
        String window,
        java.util.List<String> evidenceRefs,
        String terminationReason,
        String message) {
}
