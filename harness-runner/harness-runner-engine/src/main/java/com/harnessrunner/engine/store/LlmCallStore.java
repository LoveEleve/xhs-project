package com.harnessrunner.engine.store;

import java.util.List;

public interface LlmCallStore {

    void save(LlmCall call, String prompt, String response);

    List<LlmCall> findByChangeId(String changeId);
}
