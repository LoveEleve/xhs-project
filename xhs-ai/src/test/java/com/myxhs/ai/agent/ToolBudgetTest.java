package com.myxhs.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolBudgetTest {

    @Test
    void okWithinSoftBudget() {
        assertThat(ToolBudget.evaluate(28, 32, 40)).isEqualTo(ToolBudget.Level.OK);
    }

    @Test
    void warnBetweenSoftAndHard() {
        assertThat(ToolBudget.evaluate(33, 32, 40)).isEqualTo(ToolBudget.Level.WARN);
        assertThat(ToolBudget.evaluate(40, 32, 40)).isEqualTo(ToolBudget.Level.WARN);
    }

    @Test
    void failAboveHardBudget() {
        assertThat(ToolBudget.evaluate(41, 32, 40)).isEqualTo(ToolBudget.Level.FAIL);
        assertThat(ToolBudget.failMessage(41, 32, 40)).contains("tool_search");
    }
}
