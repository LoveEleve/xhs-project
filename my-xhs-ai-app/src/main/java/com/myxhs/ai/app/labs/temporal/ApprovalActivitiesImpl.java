package com.myxhs.ai.app.labs.temporal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** D5 Temporal PoC：最小 activity 实现，当前仅记录日志，供 kill/restart 实验。 */
public class ApprovalActivitiesImpl implements ApprovalActivities {

    private static final Logger log = LoggerFactory.getLogger(ApprovalActivitiesImpl.class);

    @Override
    public String prepareApproval(String runId, String payload) {
        log.info("[temporal-poc] prepareApproval runId={} payload={}", runId, payload);
        return "WAITING_APPROVAL";
    }

    @Override
    public String completeAfterApproval(String runId, String approver, String reason) {
        log.info("[temporal-poc] completeAfterApproval runId={} approver={} reason={}", runId, approver, reason);
        return "COMPLETED";
    }

    @Override
    public String markRejected(String runId, String approver, String reason) {
        log.info("[temporal-poc] markRejected runId={} approver={} reason={}", runId, approver, reason);
        return "REJECTED";
    }
}
