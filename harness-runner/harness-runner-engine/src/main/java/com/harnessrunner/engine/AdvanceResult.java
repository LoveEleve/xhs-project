package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.engine.store.StageRunRecord;

public record AdvanceResult(Change change, StageRunRecord stageRun, String message) {
}
