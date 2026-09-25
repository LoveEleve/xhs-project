package com.harnessrunner.engine.store;

import java.util.Optional;

public interface ChangeRepository {

    void save(ChangeRecord record);

    Optional<ChangeRecord> findById(String changeId);
}
