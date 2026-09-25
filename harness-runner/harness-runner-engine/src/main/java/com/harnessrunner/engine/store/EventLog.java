package com.harnessrunner.engine.store;

import com.harnessrunner.domain.change.ChangeEvent;

import java.util.List;

public interface EventLog {

    void append(ChangeEvent event);

    List<ChangeEvent> findByChangeId(String changeId);
}
