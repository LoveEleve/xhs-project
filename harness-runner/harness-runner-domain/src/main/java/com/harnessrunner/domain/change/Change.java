package com.harnessrunner.domain.change;

import java.util.Objects;

public class Change {

    private final String id;
    private final String projectId;
    private final String requirement;

    private ChangeStatus status;
    private Stage currentStage;
    private long version;

    public Change(String id, String projectId, String requirement) {
        this(id, projectId, requirement, ChangeStatus.CREATED, null, 0L);
    }

    private Change(String id, String projectId, String requirement,
                   ChangeStatus status, Stage currentStage, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version 不能为负: " + version);
        }
        if (status == ChangeStatus.CREATED && currentStage != null) {
            throw new IllegalArgumentException("CREATED 状态不允许有当前阶段: " + currentStage);
        }
        if ((status == ChangeStatus.IN_PROGRESS || status == ChangeStatus.AWAITING_APPROVAL
                || status == ChangeStatus.PAUSED || status == ChangeStatus.FAILED)
                && currentStage == null) {
            throw new IllegalArgumentException(status + " 状态必须有当前阶段");
        }
        this.currentStage = currentStage;
        this.version = version;
    }

    public static Change rehydrate(String id, String projectId, String requirement,
                                   ChangeStatus status, Stage currentStage, long version) {
        return new Change(id, projectId, requirement, status, currentStage, version);
    }

    public void start() {
        requireStatus(ChangeStatus.CREATED);
        transit(ChangeStatus.IN_PROGRESS);
        this.currentStage = Stage.first();
        this.version++;
    }

    public void advance() {
        requireStatus(ChangeStatus.IN_PROGRESS);
        Stage next = currentStage.next().orElse(null);
        if (next == null) {
            transit(ChangeStatus.DONE);
            this.currentStage = null;
        } else {
            this.currentStage = next;
        }
        this.version++;
    }

    public void fail() {
        requireStatus(ChangeStatus.IN_PROGRESS);
        transit(ChangeStatus.FAILED);
        this.version++;
    }

    public void retry() {
        requireStatus(ChangeStatus.FAILED);
        transit(ChangeStatus.IN_PROGRESS);
        this.version++;
    }

    public void awaitApproval() {
        requireStatus(ChangeStatus.IN_PROGRESS);
        transit(ChangeStatus.AWAITING_APPROVAL);
        this.version++;
    }

    public void approve() {
        requireStatus(ChangeStatus.AWAITING_APPROVAL);
        transit(ChangeStatus.IN_PROGRESS);
        this.version++;
        advance();
    }

    public void pause() {
        requireStatus(ChangeStatus.IN_PROGRESS);
        transit(ChangeStatus.PAUSED);
        this.version++;
    }

    public void resume() {
        requireStatus(ChangeStatus.PAUSED);
        transit(ChangeStatus.IN_PROGRESS);
        this.version++;
    }

    public void cancel() {
        transit(ChangeStatus.CANCELLED);
        this.version++;
    }

    private void requireStatus(ChangeStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateTransitionException(this.status, expected);
        }
    }

    private void transit(ChangeStatus to) {
        ChangeStateMachine.validate(this.status, to);
        this.status = to;
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String requirement() {
        return requirement;
    }

    public ChangeStatus status() {
        return status;
    }

    public Stage currentStage() {
        return currentStage;
    }

    public long version() {
        return version;
    }
}
