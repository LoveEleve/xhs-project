package com.myxhs.ai.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiRoleTest {

    @Test
    void mapsClaims() {
        assertThat(AiRole.fromClaim("ADMIN")).isEqualTo(AiRole.ADMIN);
        assertThat(AiRole.fromClaim("super_admin")).isEqualTo(AiRole.ADMIN);
        assertThat(AiRole.fromClaim("OPERATOR")).isEqualTo(AiRole.OPERATOR);
        assertThat(AiRole.fromClaim("ops")).isEqualTo(AiRole.OPERATOR);
        assertThat(AiRole.fromClaim("USER")).isEqualTo(AiRole.VIEWER);
        assertThat(AiRole.fromClaim(null)).isEqualTo(AiRole.VIEWER);
    }

    @Test
    void ordering() {
        assertThat(AiRole.ADMIN.atLeast(AiRole.OPERATOR)).isTrue();
        assertThat(AiRole.OPERATOR.atLeast(AiRole.ADMIN)).isFalse();
        assertThat(AiRole.VIEWER.atLeast(AiRole.VIEWER)).isTrue();
    }
}
