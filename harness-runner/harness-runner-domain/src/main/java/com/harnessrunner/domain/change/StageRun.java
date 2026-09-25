package com.harnessrunner.domain.change;

import java.util.Objects;

public class StageRun {

    private final String id;
    private final String changeId;
    private final Stage stage;
    private final int attempt;

    private StageRunStatus status;

    public StageRun(String id, String changeId, Stage stage, int attempt) {
        this.id = Objects.requireNonNull(id, "id");
        this.changeId = Objects.requireNonNull(changeId, "changeId");
        this.stage = Objects.requireNonNull(stage, "stage");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须 >= 1: " + attempt);
        }
        this.attempt = attempt;
        this.status = StageRunStatus.PENDING;
    }

    public String uniqueKey() {
        return changeId + ":" + stage.name() + ":" + attempt;
    }

    public void markRunning() {
        require(StageRunStatus.PENDING);
        this.status = StageRunStatus.RUNNING;
    }

    public void markPassed() {
        require(StageRunStatus.RUNNING);
        this.status = StageRunStatus.PASSED;
    }

    public void markFailed() {
        require(StageRunStatus.RUNNING);
        this.status = StageRunStatus.FAILED;
    }

    private void require(StageRunStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("StageRun 状态为 " + status + "，期望 " + expected);
        }
    }

    public String id() {
        return id;
    }

    public String changeId() {
        return changeId;
    }

    public Stage stage() {
        return stage;
    }

    public int attempt() {
        return attempt;
    }

    public StageRunStatus status() {
        return status;
    }
}
