package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeStateMachineTest {

    @Test
    void legalTransitions() {
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.CREATED, ChangeStatus.IN_PROGRESS));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.CREATED, ChangeStatus.CANCELLED));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.IN_PROGRESS, ChangeStatus.PAUSED));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.IN_PROGRESS, ChangeStatus.FAILED));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.IN_PROGRESS, ChangeStatus.DONE));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.IN_PROGRESS, ChangeStatus.AWAITING_APPROVAL));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.AWAITING_APPROVAL, ChangeStatus.IN_PROGRESS));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.AWAITING_APPROVAL, ChangeStatus.CANCELLED));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.PAUSED, ChangeStatus.IN_PROGRESS));
        assertTrue(ChangeStateMachine.canTransit(ChangeStatus.FAILED, ChangeStatus.IN_PROGRESS));
    }

    @Test
    void illegalTransitions() {
        assertFalse(ChangeStateMachine.canTransit(ChangeStatus.CREATED, ChangeStatus.DONE));
        assertFalse(ChangeStateMachine.canTransit(ChangeStatus.CREATED, ChangeStatus.FAILED));
        assertFalse(ChangeStateMachine.canTransit(ChangeStatus.PAUSED, ChangeStatus.DONE));
        assertFalse(ChangeStateMachine.canTransit(ChangeStatus.FAILED, ChangeStatus.DONE));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (ChangeStatus from : new ChangeStatus[]{ChangeStatus.DONE, ChangeStatus.CANCELLED}) {
            for (ChangeStatus to : ChangeStatus.values()) {
                assertFalse(ChangeStateMachine.canTransit(from, to), from + " -> " + to);
            }
        }
    }

    @Test
    void validateThrowsOnIllegalTransition() {
        IllegalStateTransitionException exception = assertThrows(IllegalStateTransitionException.class,
                () -> ChangeStateMachine.validate(ChangeStatus.DONE, ChangeStatus.IN_PROGRESS));
        assertEquals(ChangeStatus.DONE, exception.from());
        assertEquals(ChangeStatus.IN_PROGRESS, exception.to());
    }
}
