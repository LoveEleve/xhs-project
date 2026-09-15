package com.myxhs.ai.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyServiceTest {

    @Test
    void keyIsScopedByUserAndRequest() {
        assertThat(IdempotencyService.key(99, "req-1")).isEqualTo("xhs-ai:idem:99:req-1");
        assertThat(IdempotencyService.key(1, "req-1")).isNotEqualTo(IdempotencyService.key(99, "req-1"));
    }
}
