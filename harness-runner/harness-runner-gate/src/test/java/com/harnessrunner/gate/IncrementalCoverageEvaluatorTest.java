package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalCoverageEvaluatorTest {

    private static final Path REPORT = Path.of("src/test/resources/jacoco-lines-sample.xml");

    private final IncrementalCoverageEvaluator evaluator = new IncrementalCoverageEvaluator();

    @Test
    void countsOnlyChangedExecutableLines() {
        Map<String, List<LineRange>> changed = Map.of(
                "src/main/java/demo/Calc.java", List.of(new LineRange(3, 4), new LineRange(9, 9)));

        IncrementalCoverage coverage = evaluator.evaluate(REPORT, changed);

        assertEquals(3, coverage.executableChangedLines());
        assertEquals(2, coverage.coveredChangedLines());
        assertEquals(2.0 / 3.0, coverage.ratio(), 0.0001);
        assertEquals(66.7, coverage.percent(), 0.05);
        assertTrue(coverage.hasExecutableChanges());
    }

    @Test
    void ignoresNonExecutableLinesAndUnknownFiles() {
        Map<String, List<LineRange>> changed = Map.of(
                "src/main/java/demo/Calc.java", List.of(new LineRange(100, 101)),
                "README.md", List.of(new LineRange(1, 3)));

        IncrementalCoverage coverage = evaluator.evaluate(REPORT, changed);

        assertEquals(0, coverage.executableChangedLines());
        assertEquals(0.0, coverage.ratio(), 0.0001);
        assertFalse(coverage.hasExecutableChanges());
    }
}
