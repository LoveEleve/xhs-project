package com.myxhs.ai.app.labs.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/** D5 Temporal PoC：审批型长任务 workflow 实现。 */
public class ApprovalWorkflowImpl implements ApprovalWorkflow {

    private final ApprovalActivities activities = Workflow.newActivityStub(
            ApprovalActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    private WorkflowState state = WorkflowState.RECEIVED;
    private boolean approved;
    private boolean rejected;
    private String approver;
    private String reason;

    @Override
    public String start(String runId, String payload) {
        state = WorkflowState.RECEIVED;
        activities.prepareApproval(runId, payload);
        state = WorkflowState.WAITING_APPROVAL;

        Workflow.await(() -> approved || rejected);

        if (rejected) {
            state = WorkflowState.REJECTED;
            activities.markRejected(runId, approver, reason);
            return "REJECTED";
        }

        state = WorkflowState.APPROVED;
        activities.completeAfterApproval(runId, approver, reason);
        state = WorkflowState.COMPLETED;
        return "COMPLETED";
    }

    @Override
    public void approve(String approver, String reason) {
        this.approver = approver;
        this.reason = reason;
        this.approved = true;
    }

    @Override
    public void reject(String approver, String reason) {
        this.approver = approver;
        this.reason = reason;
        this.rejected = true;
    }

    @Override
    public WorkflowState queryState() {
        return state;
    }

    @Override
    public String toString() {
        return "ApprovalWorkflowImpl{" +
                "state=" + state +
                ", approved=" + approved +
                ", rejected=" + rejected +
                ", approver='" + approver + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}
