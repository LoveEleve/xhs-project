package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacocoReportParserTest {

    @TempDir
    Path tempDir;

    private final JacocoReportParser parser = new JacocoReportParser();

    @Test
    void parsesReportLevelAggregateWithDoctype() {
        Path report = Path.of("src/test/resources/jacoco-sample.xml");
        CoverageSummary summary = parser.parse(report).orElseThrow();
        assertEquals(80, summary.lineCovered());
        assertEquals(20, summary.lineMissed());
        assertEquals(0.8, summary.lineRatio(), 0.0001);
        assertEquals(80.0, summary.linePercent(), 0.0001);
        assertEquals(15, summary.branchCovered());
        assertEquals(5, summary.branchMissed());
        assertEquals(75.0, summary.branchPercent(), 0.0001);
        assertTrue(summary.hasLineData());
        assertTrue(summary.hasBranchData());
    }

    @Test
    void parsesReportWithoutBranchCounter() throws IOException {
        Path report = tempDir.resolve("jacoco.xml");
        Files.writeString(report, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <report name="demo">
                    <counter type="LINE" missed="1" covered="2"/>
                </report>
                """);

        CoverageSummary summary = parser.parse(report).orElseThrow();
        assertEquals(2, summary.lineCovered());
        assertTrue(summary.hasLineData());
        assertFalse(summary.hasBranchData());
    }

    @Test
    void emptyWhenFileMissing() {
        assertTrue(parser.parse(Path.of("src/test/resources/no-such-report.xml")).isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }
}
