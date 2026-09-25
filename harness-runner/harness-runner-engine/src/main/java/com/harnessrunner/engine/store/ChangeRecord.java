package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeStatus;
import com.harnessrunner.domain.change.Stage;

import java.util.Objects;

public record ChangeRecord(
        String id,
        String projectId,
        String requirement,
        ChangeStatus status,
        Stage currentStage,
        long version,
        String projectDir,
        Double minLineCoverage) {

    public ChangeRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(projectDir, "projectDir");
    }

    public static ChangeRecord of(Change change, String projectDir, Double minLineCoverage) {
        return new ChangeRecord(change.id(), change.projectId(), change.requirement(),
                change.status(), change.currentStage(), change.version(), projectDir, minLineCoverage);
    }

    public Change toChange() {
        return Change.rehydrate(id, projectId, requirement, status, currentStage, version);
    }
}
