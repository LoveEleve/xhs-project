package com.harnessrunner.domain.change;

public enum ChangeStatus {
    CREATED,
    IN_PROGRESS,
    AWAITING_APPROVAL,
    PAUSED,
    FAILED,
    DONE,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }
}
