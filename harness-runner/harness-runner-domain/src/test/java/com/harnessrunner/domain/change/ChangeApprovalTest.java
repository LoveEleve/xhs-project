package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeApprovalTest {

    private Change inProgress() {
        Change change = new Change("chg-1", "proj-1", "需求");
        change.start();
        return change;
    }

    @Test
    void awaitingApprovalKeepsCurrentStage() {
        Change change = inProgress();

        change.awaitApproval();

        assertEquals(ChangeStatus.AWAITING_APPROVAL, change.status());
        assertEquals(Stage.HARNESSING, change.currentStage());
        assertEquals(2L, change.version());
    }

    @Test
    void approveResumesAndAdvancesToNextStage() {
        Change change = inProgress();
        change.awaitApproval();

        change.approve();

        assertEquals(ChangeStatus.IN_PROGRESS, change.status());
        assertEquals(Stage.CODING, change.currentStage());
        assertEquals(4L, change.version());
    }

    @Test
    void approveRequiresAwaitingState() {
        Change change = inProgress();

        assertThrows(IllegalStateTransitionException.class, change::approve);
    }

    @Test
    void cannotAwaitApprovalTwice() {
        Change change = inProgress();
        change.awaitApproval();

        assertThrows(IllegalStateTransitionException.class, change::awaitApproval);
    }

    @Test
    void awaitingApprovalCanBeCancelled() {
        Change change = inProgress();
        change.awaitApproval();

        change.cancel();

        assertEquals(ChangeStatus.CANCELLED, change.status());
    }
}
