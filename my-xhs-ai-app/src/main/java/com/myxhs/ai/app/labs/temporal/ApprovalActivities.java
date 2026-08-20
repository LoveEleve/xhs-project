package com.myxhs.ai.app.labs.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** D5 Temporal PoC：审批前准备 / 审批后执行 activity。 */
@ActivityInterface
public interface ApprovalActivities {

    @ActivityMethod
    String prepareApproval(String runId, String payload);

    @ActivityMethod
    String completeAfterApproval(String runId, String approver, String reason);

    @ActivityMethod
    String markRejected(String runId, String approver, String reason);
}
