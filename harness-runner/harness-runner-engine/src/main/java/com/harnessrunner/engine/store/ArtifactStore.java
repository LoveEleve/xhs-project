package com.harnessrunner.engine.store;

import com.harnessrunner.domain.artifact.Artifact;
import com.harnessrunner.domain.artifact.ArtifactType;

import java.util.List;
import java.util.Optional;

public interface ArtifactStore {

    Artifact save(String changeId, ArtifactType type, String content);

    List<Artifact> list(String changeId);

    Optional<Artifact> latest(String changeId, ArtifactType type);
}
