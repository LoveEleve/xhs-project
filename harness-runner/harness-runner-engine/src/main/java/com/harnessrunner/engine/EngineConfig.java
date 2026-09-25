package com.harnessrunner.engine;

import java.nio.file.Path;
import java.util.Objects;

public record EngineConfig(Path projectDir, Double minLineCoverage) {

    public EngineConfig {
        Objects.requireNonNull(projectDir, "projectDir");
        if (minLineCoverage != null && (minLineCoverage < 0.0 || minLineCoverage > 1.0)) {
            throw new IllegalArgumentException("minLineCoverage 必须在 [0,1]: " + minLineCoverage);
        }
    }

    public static EngineConfig of(Path projectDir) {
        return new EngineConfig(projectDir, null);
    }
}
