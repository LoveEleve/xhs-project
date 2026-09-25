package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.ChangeEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EventHasher {

    public static final String GENESIS = "GENESIS";

    private EventHasher() {
    }

    public static String canonical(ChangeEvent event) {
        return String.join("|",
                escape(event.changeId()),
                event.type().name(),
                event.stage() == null ? "" : event.stage().name(),
                escape(event.message()),
                event.occurredAt().toString());
    }

    public static String hash(ChangeEvent event, String prevHash) {
        String chainRoot = prevHash == null || prevHash.isBlank() ? GENESIS : prevHash;
        return sha256(canonical(event) + "|" + chainRoot);
    }

    private static String escape(String value) {
        return value.replace("|", "\\|");
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
