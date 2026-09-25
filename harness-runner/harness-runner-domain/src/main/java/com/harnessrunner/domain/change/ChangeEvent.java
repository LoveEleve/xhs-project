package com.harnessrunner.domain.change;

import java.time.Instant;
import java.util.Objects;

public record ChangeEvent(
        String changeId,
        ChangeEventType type,
        Stage stage,
        String message,
        Instant occurredAt,
        String prevHash,
        String hash) {

    public ChangeEvent {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static ChangeEvent of(String changeId, ChangeEventType type, Stage stage, String message) {
        return new ChangeEvent(changeId, type, stage, message, Instant.now(), null, null);
    }

    public ChangeEvent withChain(String prevHash, String hash) {
        return new ChangeEvent(changeId, type, stage, message, occurredAt, prevHash, hash);
    }

    public boolean hashed() {
        return hash != null;
    }
}
