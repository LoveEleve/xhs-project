package com.myxhs.ai.app.service.agent.harness;

/**
 * Run 状态（设计 §3.5 运行状态机的 V1 简化版，同步执行）：
 * RUNNING → SUCCEEDED / PARTIAL / FAILED / CANCELLED。
 */
public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED
}
