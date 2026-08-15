package com.myxhs.ai.app.service.agent.harness;

/**
 * Run 状态（设计 §3.5 运行状态机）：RUNNING → SUCCEEDED / PARTIAL / FAILED / CANCELLED；
 * M11 HITL 新增 WAITING_APPROVAL（L3 工具审批挂起，审批后 resume 回 RUNNING 继续）。
 */
public enum RunStatus {
    RUNNING,
    /** M11：L3 工具待人工审批（挂起：线程已释放，状态落库，审批后 resume） */
    WAITING_APPROVAL,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED
}
