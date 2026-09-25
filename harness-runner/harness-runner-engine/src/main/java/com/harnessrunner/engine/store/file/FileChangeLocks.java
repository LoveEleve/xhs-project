package com.harnessrunner.engine.store.file;

import com.harnessrunner.engine.store.ChangeLocks;
import com.harnessrunner.engine.store.OptimisticLockException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FileChangeLocks implements ChangeLocks {

    private static final ConcurrentHashMap<String, Semaphore> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Workspace workspace;
    private final Duration waitTimeout;

    public FileChangeLocks(Workspace workspace) {
        this(workspace, Duration.ofSeconds(10));
    }

    public FileChangeLocks(Workspace workspace, Duration waitTimeout) {
        this.workspace = workspace;
        this.waitTimeout = waitTimeout;
    }

    @Override
    public Handle acquire(String changeId) {
        Path lockFile = workspace.changeDir(changeId).resolve("change.lock");
        Semaphore jvmLock = JVM_LOCKS.computeIfAbsent(lockFile.toString(), key -> new Semaphore(1));
        if (!tryAcquireJvmLock(changeId, jvmLock)) {
            throw new OptimisticLockException(changeId, "同进程内已有推进在进行");
        }
        try {
            Files.createDirectories(lockFile.getParent());
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock;
            try {
                fileLock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw new OptimisticLockException(changeId, "同进程内文件锁重叠");
            }
            if (fileLock == null) {
                channel.close();
                throw new OptimisticLockException(changeId, "另一个进程正在推进该变更");
            }
            return new FileHandle(jvmLock, channel, fileLock, new AtomicBoolean(false));
        } catch (IOException e) {
            jvmLock.release();
            throw new UncheckedIOException("创建锁文件失败: " + lockFile, e);
        } catch (RuntimeException e) {
            jvmLock.release();
            throw e;
        }
    }

    private boolean tryAcquireJvmLock(String changeId, Semaphore jvmLock) {
        try {
            return jvmLock.tryAcquire(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OptimisticLockException(changeId, "等待锁被中断");
        }
    }

    private record FileHandle(Semaphore jvmLock, FileChannel channel, FileLock fileLock,
                              AtomicBoolean closed) implements Handle {

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                fileLock.release();
            } catch (IOException ignored) {
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            jvmLock.release();
        }
    }
}
