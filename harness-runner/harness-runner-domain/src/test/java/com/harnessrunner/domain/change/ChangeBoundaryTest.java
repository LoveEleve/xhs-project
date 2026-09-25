package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeBoundaryTest {

    private Change newChange() {
        return new Change("chg-1", "proj-1", "边界用例");
    }

    @Test
    void retryRequiresFailedState() {
        Change change = newChange();
        change.start();
        assertThrows(IllegalStateTransitionException.class, change::retry);
    }

    @Test
    void resumeRequiresPausedState() {
        Change change = newChange();
        change.start();
        assertThrows(IllegalStateTransitionException.class, change::resume);
    }

    @Test
    void doneIsTerminalForAllCommands() {
        Change change = newChange();
        change.start();
        for (int i = 0; i < 6; i++) {
            change.advance();
        }
        assertEquals(ChangeStatus.DONE, change.status());

        assertThrows(IllegalStateTransitionException.class, change::advance);
        assertThrows(IllegalStateTransitionException.class, change::fail);
        assertThrows(IllegalStateTransitionException.class, change::pause);
        assertThrows(IllegalStateTransitionException.class, change::cancel);
        assertThrows(IllegalStateTransitionException.class, change::retry);
    }

    @Test
    void pausedChangeCannotAdvanceOrFail() {
        Change change = newChange();
        change.start();
        change.pause();

        assertThrows(IllegalStateTransitionException.class, change::advance);
        assertThrows(IllegalStateTransitionException.class, change::fail);

        change.resume();
        change.advance();
        assertEquals(Stage.CODING, change.currentStage());
    }

    @Test
    void rejectedTransitionDoesNotBumpVersionOrChangeStage() {
        Change change = newChange();
        change.start();
        long versionBefore = change.version();
        Stage stageBefore = change.currentStage();

        assertThrows(IllegalStateTransitionException.class, change::retry);
        assertThrows(IllegalStateTransitionException.class, change::resume);

        assertEquals(versionBefore, change.version());
        assertEquals(stageBefore, change.currentStage());
        assertEquals(ChangeStatus.IN_PROGRESS, change.status());
    }
}
