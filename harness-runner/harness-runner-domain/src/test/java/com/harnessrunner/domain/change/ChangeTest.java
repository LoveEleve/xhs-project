package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeTest {

    private Change newChange() {
        return new Change("chg-1", "proj-1", "完善读写分离");
    }

    @Test
    void newChangeStartsInCreatedState() {
        Change change = newChange();
        assertEquals(ChangeStatus.CREATED, change.status());
        assertNull(change.currentStage());
        assertEquals(0L, change.version());
    }

    @Test
    void startEntersFirstStage() {
        Change change = newChange();
        change.start();
        assertEquals(ChangeStatus.IN_PROGRESS, change.status());
        assertEquals(Stage.HARNESSING, change.currentStage());
        assertEquals(1L, change.version());
    }

    @Test
    void fullHappyPathEndsInDone() {
        Change change = newChange();
        change.start();

        Stage[] expectedStages = {
                Stage.CODING, Stage.TEST_WRITE, Stage.REVIEW, Stage.CI, Stage.DEPLOY_VERIFY
        };
        for (Stage stage : expectedStages) {
            change.advance();
            assertEquals(stage, change.currentStage());
        }

        change.advance();
        assertEquals(ChangeStatus.DONE, change.status());
        assertNull(change.currentStage());
    }

    @Test
    void failThenRetryStaysOnSameStage() {
        Change change = newChange();
        change.start();
        change.advance();
        assertEquals(Stage.CODING, change.currentStage());

        change.fail();
        assertEquals(ChangeStatus.FAILED, change.status());
        assertEquals(Stage.CODING, change.currentStage());

        change.retry();
        assertEquals(ChangeStatus.IN_PROGRESS, change.status());
        assertEquals(Stage.CODING, change.currentStage());
    }

    @Test
    void pauseAndResume() {
        Change change = newChange();
        change.start();
        change.pause();
        assertEquals(ChangeStatus.PAUSED, change.status());

        change.resume();
        assertEquals(ChangeStatus.IN_PROGRESS, change.status());
        assertEquals(Stage.HARNESSING, change.currentStage());
    }

    @Test
    void cannotAdvanceWhenFailed() {
        Change change = newChange();
        change.start();
        change.fail();
        assertThrows(IllegalStateTransitionException.class, change::advance);
    }

    @Test
    void cannotStartTwice() {
        Change change = newChange();
        change.start();
        assertThrows(IllegalStateTransitionException.class, change::start);
    }

    @Test
    void cancelledIsTerminal() {
        Change change = newChange();
        change.start();
        change.cancel();
        assertEquals(ChangeStatus.CANCELLED, change.status());
        assertThrows(IllegalStateTransitionException.class, change::advance);
        assertThrows(IllegalStateTransitionException.class, change::start);
    }

    @Test
    void versionIncrementsOnEveryTransition() {
        Change change = newChange();
        assertEquals(0L, change.version());

        change.start();
        assertEquals(1L, change.version());

        change.advance();
        assertEquals(2L, change.version());

        change.fail();
        assertEquals(3L, change.version());

        change.retry();
        assertEquals(4L, change.version());
    }
}
