package com.myxhs.ai.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {

    @Test
    void okBelowSoft() {
        assertThat(TokenBudget.decide(0, 80, 100)).isEqualTo(TokenBudget.Decision.OK);
        assertThat(TokenBudget.decide(79, 80, 100)).isEqualTo(TokenBudget.Decision.OK);
    }

    @Test
    void softBetweenSoftAndHard() {
        assertThat(TokenBudget.decide(80, 80, 100)).isEqualTo(TokenBudget.Decision.SOFT);
        assertThat(TokenBudget.decide(99, 80, 100)).isEqualTo(TokenBudget.Decision.SOFT);
    }

    @Test
    void hardAtLimit() {
        assertThat(TokenBudget.decide(100, 80, 100)).isEqualTo(TokenBudget.Decision.HARD);
    }
}
