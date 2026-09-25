package com.harnessrunner.domain.gate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateResultTest {

    @Test
    void preservesMetricInsertionOrder() {
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("testsRun", 93);
        metrics.put("failures", 0);
        metrics.put("linePercent", 76.4);

        GateResult result = new GateResult(GateType.TEST, true, "exitCode=0", "mvn test", 0, 100L,
                false, "out", false, null, metrics);

        assertEquals(List.of("testsRun", "failures", "linePercent"),
                List.copyOf(result.metrics().keySet()));
        assertEquals(93, result.metric("testsRun").intValue());
    }

    @Test
    void nullMetricsBecomeEmptyImmutableMap() {
        GateResult result = new GateResult(GateType.COMPILE, true, "exitCode=0", "mvn compile", 0, 1L,
                false, "", false, null, null);

        assertTrue(result.metrics().isEmpty());
        assertNull(result.metric("testsRun"));
        assertThrows(UnsupportedOperationException.class, () -> result.metrics().put("x", 1));
    }
}
