package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageRunTest {

    @Test
    void lifecyclePendingToRunningToPassed() {
        StageRun run = new StageRun("run-1", "chg-1", Stage.CODING, 1);
        assertEquals(StageRunStatus.PENDING, run.status());

        run.markRunning();
        assertEquals(StageRunStatus.RUNNING, run.status());

        run.markPassed();
        assertEquals(StageRunStatus.PASSED, run.status());
    }

    @Test
    void cannotPassWithoutRunning() {
        StageRun run = new StageRun("run-1", "chg-1", Stage.CODING, 1);
        assertThrows(IllegalStateException.class, run::markPassed);
    }

    @Test
    void attemptMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new StageRun("run-1", "chg-1", Stage.CODING, 0));
    }

    @Test
    void uniqueKeyContainsChangeStageAndAttempt() {
        StageRun first = new StageRun("run-1", "chg-1", Stage.CODING, 1);
        StageRun second = new StageRun("run-2", "chg-1", Stage.CODING, 2);
        assertEquals("chg-1:CODING:1", first.uniqueKey());
        assertNotEquals(first.uniqueKey(), second.uniqueKey());
    }
}
