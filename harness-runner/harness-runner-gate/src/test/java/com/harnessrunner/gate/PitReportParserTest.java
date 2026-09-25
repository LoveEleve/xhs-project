package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PitReportParserTest {

    private final PitReportParser parser = new PitReportParser();

    @Test
    void countsKilledSurvivedAndNoCoverage() {
        Path report = Path.of("src/test/resources/pit-mutations-sample.xml");

        MutationSummary summary = parser.parse(report).orElseThrow();

        assertEquals(3, summary.killed());
        assertEquals(2, summary.survived());
        assertEquals(1, summary.noCoverage());
        assertEquals(6, summary.total());
        assertEquals(0.5, summary.score(), 0.0001);
        assertEquals(50.0, summary.percent(), 0.0001);
        assertTrue(summary.hasData());
    }

    @Test
    void emptyWhenFileMissingOrNull() {
        assertTrue(parser.parse(Path.of("src/test/resources/no-such-pit-report.xml")).isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void hasNoDataWhenOnlyIgnoredStatuses() {
        MutationSummary summary = new MutationSummary(0, 0, 0);

        assertFalse(summary.hasData());
        assertEquals(0.0, summary.score(), 0.0001);
    }
}
