package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.Stage;

import java.util.List;

public interface StageRunRepository {

    void save(StageRunRecord record);

    List<StageRunRecord> findByChangeId(String changeId);

    int nextAttempt(String changeId, Stage stage);
}
