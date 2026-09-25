package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireOutputParserTest {

    private final SurefireOutputParser parser = new SurefireOutputParser();

    @Test
    void takesLastSummaryAsTotal() {
        String output = """
                [INFO] Running com.myxhs.common.zone.ZonePreferenceFilterTest
                [INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.056 s -- in ZonePreferenceFilterTest
                [INFO] Running com.myxhs.common.cache.CacheHelperTest
                [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.619 s -- in CacheHelperTest
                [INFO]
                [INFO] Results:
                [INFO]
                [INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0
                """;
        TestSummary summary = parser.parse(output).orElseThrow();
        assertEquals(93, summary.testsRun());
        assertEquals(0, summary.failed());
        assertTrue(summary.allPassed());
    }

    @Test
    void capturesFailuresAndErrors() {
        TestSummary summary = parser.parse("Tests run: 10, Failures: 2, Errors: 1, Skipped: 3")
                .orElseThrow();
        assertEquals(10, summary.testsRun());
        assertEquals(2, summary.failures());
        assertEquals(1, summary.errors());
        assertEquals(3, summary.skipped());
        assertEquals(3, summary.failed());
    }

    @Test
    void emptyWhenNoSummary() {
        assertTrue(parser.parse("BUILD SUCCESS").isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }
}
