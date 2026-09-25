package com.harnessrunner.engine.store.file;

import com.harnessrunner.domain.artifact.ArtifactType;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class Workspace {

    private static final Pattern CHANGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path changesDir() {
        return root.resolve("changes");
    }

    public Path changeDir(String changeId) {
        if (changeId == null || !CHANGE_ID.matcher(changeId).matches()) {
            throw new IllegalArgumentException("非法变更 ID: " + changeId);
        }
        return changesDir().resolve(changeId);
    }

    public Path changeFile(String changeId) {
        return changeDir(changeId).resolve("change.json");
    }

    public Path stageRunsFile(String changeId) {
        return changeDir(changeId).resolve("stage-runs.json");
    }

    public Path eventsFile(String changeId) {
        return changeDir(changeId).resolve("events.jsonl");
    }

    public Path llmDir(String changeId) {
        return changeDir(changeId).resolve("llm");
    }

    public Path llmCallsFile(String changeId) {
        return changeDir(changeId).resolve("llm-calls.jsonl");
    }

    public Path artifactDir(String changeId, ArtifactType type) {
        return changeDir(changeId).resolve("artifacts").resolve(type.name().toLowerCase());
    }
}
