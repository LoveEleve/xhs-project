package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.domain.change.ChangeEventType;
import com.harnessrunner.domain.change.Stage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EventHasherTest {

    private static ChangeEvent event(String message) {
        return new ChangeEvent("chg-1", ChangeEventType.STAGE_PASSED, Stage.CI, message,
                Instant.parse("2026-09-25T10:00:00Z"), null, null);
    }

    @Test
    void hashIsDeterministicAndSixtyFourHex() {
        String first = EventHasher.hash(event("CI=PASS"), EventHasher.GENESIS);
        String second = EventHasher.hash(event("CI=PASS"), EventHasher.GENESIS);

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void hashChangesWithContentOrPreviousHash() {
        String base = EventHasher.hash(event("CI=PASS"), EventHasher.GENESIS);

        assertNotEquals(base, EventHasher.hash(event("CI=FAIL"), EventHasher.GENESIS));
        assertNotEquals(base, EventHasher.hash(event("CI=PASS"), "another-hash"));
        assertEquals(base, EventHasher.hash(event("CI=PASS"), null));
    }
}
