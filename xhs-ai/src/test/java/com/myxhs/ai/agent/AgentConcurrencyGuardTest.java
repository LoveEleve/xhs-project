package com.myxhs.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class AgentConcurrencyGuardTest {

    private AgentConcurrencyGuard guard(int perUser, int global) {
        AgentConcurrencyGuard g = new AgentConcurrencyGuard();
        ReflectionTestUtils.setField(g, "maxPerUser", perUser);
        ReflectionTestUtils.setField(g, "maxGlobal", global);
        return g;
    }

    @Test
    void perUserLimitRejectsThird() {
        AgentConcurrencyGuard g = guard(2, 10);
        g.acquire(1); g.acquire(1);
        assertThatThrownBy(() -> g.acquire(1))
                .isInstanceOf(AgentConcurrencyGuard.TooManyRequestsException.class)
                .hasMessageContaining("单用户上限");
        assertThat(g.userCount(1)).isEqualTo(2);
        g.release(1);
        g.acquire(1); // 释放后可再进
        assertThat(g.userCount(1)).isEqualTo(2);
    }

    @Test
    void globalLimitRejectsAcrossUsers() {
        AgentConcurrencyGuard g = guard(5, 2);
        g.acquire(1); g.acquire(2);
        assertThatThrownBy(() -> g.acquire(3))
                .isInstanceOf(AgentConcurrencyGuard.TooManyRequestsException.class)
                .hasMessageContaining("全局上限");
        g.release(1);
        g.acquire(3);
        assertThat(g.globalCount()).isEqualTo(2);
    }
}
