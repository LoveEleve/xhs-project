package com.harnessrunner.domain.gate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GateResult(
        GateType type,
        boolean passed,
        String reason,
        String command,
        int exitCode,
        long durationMs,
        boolean timedOut,
        String outputExcerpt,
        boolean outputTruncated,
        String outputRef,
        Map<String, Number> metrics) {

    public GateResult {
        metrics = metrics == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    public Number metric(String name) {
        return metrics.get(name);
    }
}
