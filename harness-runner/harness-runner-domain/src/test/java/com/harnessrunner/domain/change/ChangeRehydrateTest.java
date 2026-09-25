package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeRehydrateTest {

    @Test
    void restoresInProgressState() {
        Change change = Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.IN_PROGRESS, Stage.REVIEW, 7L);

        assertEquals(ChangeStatus.IN_PROGRESS, change.status());
        assertEquals(Stage.REVIEW, change.currentStage());
        assertEquals(7L, change.version());
        assertEquals("chg-1", change.id());
    }

    @Test
    void restoredChangeCanContinueTransitions() {
        Change change = Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.IN_PROGRESS, Stage.REVIEW, 7L);

        change.advance();

        assertEquals(Stage.CI, change.currentStage());
        assertEquals(8L, change.version());
    }

    @Test
    void restoresTerminalState() {
        Change change = Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.DONE, null, 12L);

        assertEquals(ChangeStatus.DONE, change.status());
        assertNull(change.currentStage());
        assertThrows(IllegalStateTransitionException.class, change::advance);
    }

    @Test
    void rejectsInconsistentState() {
        assertThrows(IllegalArgumentException.class,
                () -> Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.CREATED, Stage.CODING, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.IN_PROGRESS, null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.AWAITING_APPROVAL, null, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> Change.rehydrate("chg-1", "proj-1", "需求", ChangeStatus.IN_PROGRESS, Stage.CODING, -1L));
    }

    @Test
    void restoresAwaitingApprovalState() {
        Change change = Change.rehydrate("chg-1", "proj-1", "需求",
                ChangeStatus.AWAITING_APPROVAL, Stage.HARNESSING, 2L);

        assertEquals(ChangeStatus.AWAITING_APPROVAL, change.status());
        assertEquals(Stage.HARNESSING, change.currentStage());
    }
}
