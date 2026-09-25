package com.harnessrunner.gate;

import com.harnessrunner.domain.gate.GateType;

import java.util.Objects;

public record GateSpec(GateType type, GateCommand command, Double minScore, String diffBase) {

    public GateSpec {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(command, "command");
        if (minScore != null && (minScore < 0.0 || minScore > 1.0)) {
            throw new IllegalArgumentException("minScore 必须在 [0,1]: " + minScore);
        }
        if (type == GateType.DIFF_COVERAGE && (diffBase == null || diffBase.isBlank())) {
            throw new IllegalArgumentException("DIFF_COVERAGE 必须提供 diffBase（基线 ref）");
        }
    }

    public static GateSpec of(GateType type, GateCommand command) {
        return new GateSpec(type, command, null, null);
    }

    public static GateSpec coverage(GateCommand command, double minLineCoverage) {
        return new GateSpec(GateType.COVERAGE, command, minLineCoverage, null);
    }

    public static GateSpec diffCoverage(GateCommand command, String diffBase, double minScore) {
        return new GateSpec(GateType.DIFF_COVERAGE, command, minScore, diffBase);
    }

    public static GateSpec mutation(GateCommand command, double minMutationScore) {
        return new GateSpec(GateType.MUTATION, command, minMutationScore, null);
    }
}
