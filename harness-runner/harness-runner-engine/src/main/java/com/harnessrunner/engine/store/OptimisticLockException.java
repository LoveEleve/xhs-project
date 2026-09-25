package com.harnessrunner.engine.store;

public class OptimisticLockException extends IllegalStateException {

    public OptimisticLockException(String changeId, String message) {
        super("变更 " + changeId + " 并发冲突: " + message);
    }
}
