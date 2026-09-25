package com.harnessrunner.domain.artifact;

import java.time.Instant;
import java.util.Objects;

public record Artifact(
        String changeId,
        ArtifactType type,
        int version,
        String path,
        String sha256,
        Instant createdAt) {

    public Artifact {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(type, "type");
        if (version < 1) {
            throw new IllegalArgumentException("version 必须 >= 1: " + version);
        }
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
