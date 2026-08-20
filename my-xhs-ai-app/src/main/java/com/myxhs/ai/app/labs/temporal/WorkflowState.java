package com.myxhs.ai.app.labs.temporal;

/** D5 Temporal PoC：审批型长任务状态。 */
public enum WorkflowState {
    RECEIVED,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED
}
