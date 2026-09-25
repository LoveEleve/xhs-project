package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.Stage;

import java.time.Instant;

public record LlmCall(
        String id,
        String changeId,
        Stage stage,
        String model,
        int promptChars,
        int outputChars,
        long latencyMs,
        LlmCallStatus status,
        Instant createdAt) {
}
