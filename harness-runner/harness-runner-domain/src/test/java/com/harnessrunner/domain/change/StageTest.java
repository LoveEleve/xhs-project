package com.harnessrunner.domain.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageTest {

    @Test
    void firstStageIsHarnessing() {
        assertEquals(Stage.HARNESSING, Stage.first());
    }

    @Test
    void nextFollowsDeclaredOrder() {
        assertEquals(Stage.CODING, Stage.HARNESSING.next().orElseThrow());
        assertEquals(Stage.TEST_WRITE, Stage.CODING.next().orElseThrow());
        assertEquals(Stage.REVIEW, Stage.TEST_WRITE.next().orElseThrow());
        assertEquals(Stage.CI, Stage.REVIEW.next().orElseThrow());
        assertEquals(Stage.DEPLOY_VERIFY, Stage.CI.next().orElseThrow());
    }

    @Test
    void lastStageHasNoNext() {
        assertTrue(Stage.DEPLOY_VERIFY.next().isEmpty());
        assertTrue(Stage.DEPLOY_VERIFY.isLast());
    }

    @Test
    void ordersAreContiguousStartingAtOne() {
        int expected = 1;
        for (Stage stage : Stage.values()) {
            assertEquals(expected++, stage.order());
        }
    }
}
