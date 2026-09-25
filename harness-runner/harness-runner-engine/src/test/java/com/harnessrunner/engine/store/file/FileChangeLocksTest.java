package com.harnessrunner.engine.store.file;

import com.harnessrunner.engine.store.ChangeLocks;
import com.harnessrunner.engine.store.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChangeLocksTest {

    @TempDir
    Path tempDir;

    @Test
    void excludesSecondHolder() {
        FileChangeLocks locks = new FileChangeLocks(new Workspace(tempDir.resolve("data")),
                Duration.ofMillis(50));

        try (ChangeLocks.Handle first = locks.acquire("chg-1")) {
            assertNotNull(first);
            OptimisticLockException exception = assertThrows(OptimisticLockException.class,
                    () -> locks.acquire("chg-1"));
            assertTrue(exception.getMessage().contains("并发冲突"), exception.getMessage());
        }

        try (ChangeLocks.Handle again = locks.acquire("chg-1")) {
            assertNotNull(again);
        }
    }

    @Test
    void differentChangesDoNotBlockEachOther() {
        FileChangeLocks locks = new FileChangeLocks(new Workspace(tempDir.resolve("data")),
                Duration.ofMillis(50));

        try (ChangeLocks.Handle first = locks.acquire("chg-1");
             ChangeLocks.Handle second = locks.acquire("chg-2")) {
            assertNotNull(first);
            assertNotNull(second);
        }
    }

    @Test
    void doubleCloseDoesNotBreakMutualExclusion() {
        FileChangeLocks locks = new FileChangeLocks(new Workspace(tempDir.resolve("data")),
                Duration.ofMillis(50));
        ChangeLocks.Handle handle = locks.acquire("chg-1");
        handle.close();
        handle.close();

        try (ChangeLocks.Handle held = locks.acquire("chg-1")) {
            assertNotNull(held);
            assertThrows(OptimisticLockException.class, () -> locks.acquire("chg-1"));
        }
    }

    @Test
    void waitingAcquirerSucceedsAfterRelease() throws Exception {
        FileChangeLocks locks = new FileChangeLocks(new Workspace(tempDir.resolve("data")),
                Duration.ofSeconds(2));
        ChangeLocks.Handle first = locks.acquire("chg-1");

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            first.close();
        });
        releaser.start();

        try (ChangeLocks.Handle second = locks.acquire("chg-1")) {
            assertNotNull(second);
        }
        releaser.join();
    }
}
