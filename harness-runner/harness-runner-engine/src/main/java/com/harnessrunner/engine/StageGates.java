package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.domain.gate.GateType;
import com.harnessrunner.gate.GateSpec;
import com.harnessrunner.gate.MavenGateCommandFactory;

import java.util.List;

public final class StageGates {

    private StageGates() {
    }

    public static List<GateSpec> gatesFor(Stage stage, EngineConfig config) {
        MavenGateCommandFactory maven = new MavenGateCommandFactory();
        return switch (stage) {
            case CODING -> List.of(
                    GateSpec.of(GateType.COMPILE, maven.compile(config.projectDir())));
            case TEST_WRITE -> List.of(
                    GateSpec.of(GateType.TEST, maven.test(config.projectDir())));
            case CI -> List.of(
                    GateSpec.of(GateType.COMPILE, maven.compile(config.projectDir())),
                    config.minLineCoverage() == null
                            ? GateSpec.of(GateType.COVERAGE, maven.coverage(config.projectDir()))
                            : GateSpec.coverage(maven.coverage(config.projectDir()), config.minLineCoverage()));
            default -> List.of();
        };
    }
}
