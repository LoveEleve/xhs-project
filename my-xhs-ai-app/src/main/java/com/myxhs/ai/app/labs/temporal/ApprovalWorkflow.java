package com.myxhs.ai.app.labs.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** D5 Temporal PoC：审批型长任务 workflow。 */
@WorkflowInterface
public interface ApprovalWorkflow {

    @WorkflowMethod
    String start(String runId, String payload);

    @SignalMethod
    void approve(String approver, String reason);

    @SignalMethod
    void reject(String approver, String reason);

    @QueryMethod
    WorkflowState queryState();
}
