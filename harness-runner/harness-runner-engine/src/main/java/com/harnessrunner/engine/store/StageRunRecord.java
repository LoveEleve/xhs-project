package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.domain.change.StageRunStatus;
import com.harnessrunner.domain.gate.GateResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record StageRunRecord(
        String id,
        String changeId,
        Stage stage,
        int attempt,
        StageRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        List<GateResult> gates) {

    public StageRunRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(stage, "stage");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须 >= 1: " + attempt);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        gates = gates == null ? List.of() : List.copyOf(gates);
    }

    public boolean passed() {
        return status == StageRunStatus.PASSED;
    }
}
