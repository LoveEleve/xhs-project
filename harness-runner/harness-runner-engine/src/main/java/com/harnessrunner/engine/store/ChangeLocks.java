package com.harnessrunner.engine.store;

public interface ChangeLocks {

    Handle acquire(String changeId);

    interface Handle extends AutoCloseable {

        @Override
        void close();
    }
}
