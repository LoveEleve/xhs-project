package com.harnessrunner.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmConfigTest {

    @Test
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class, () -> new LlmConfig("", "key", "model", null));
        assertThrows(IllegalArgumentException.class, () -> new LlmConfig("http://x", " ", "model", null));
        assertThrows(IllegalArgumentException.class, () -> new LlmConfig("http://x", "key", "", null));
    }

    @Test
    void defaultsTimeoutWhenNull() {
        LlmConfig config = new LlmConfig("http://x", "key", "model", null);

        assertEquals(Duration.ofSeconds(60), config.timeout());
    }
}
