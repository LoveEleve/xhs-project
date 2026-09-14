package com.myxhs.ai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalFingerprintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sameSemanticsDifferentKeyOrderHasSameFingerprint() throws Exception {
        String a = "{\"topic\":\"CART_TOPIC\",\"msgId\":\"m-1\"}";
        String b = "{\"msgId\":\"m-1\",\"topic\":\"CART_TOPIC\"}";
        assertThat(ApprovalFingerprint.of(a, objectMapper))
                .isEqualTo(ApprovalFingerprint.of(b, objectMapper));
    }

    @Test
    void tamperedValueChangesFingerprint() throws Exception {
        String original = "{\"msgId\":\"m-1\",\"topic\":\"CART_TOPIC\"}";
        String tampered = "{\"msgId\":\"m-2\",\"topic\":\"CART_TOPIC\"}";
        String hash = ApprovalFingerprint.of(original, objectMapper);
        assertThat(ApprovalFingerprint.matches(original, hash, objectMapper)).isTrue();
        assertThat(ApprovalFingerprint.matches(tampered, hash, objectMapper)).isFalse();
    }
}
