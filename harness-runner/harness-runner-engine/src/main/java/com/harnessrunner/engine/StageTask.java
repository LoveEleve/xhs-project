package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.gate.GateResult;

import java.util.List;

public interface StageTask {

    StageTaskOutcome run(Change change, EngineConfig config);

    record StageTaskOutcome(List<GateResult> gates, String artifactBody) {

        public StageTaskOutcome {
            gates = gates == null ? List.of() : List.copyOf(gates);
            artifactBody = artifactBody == null ? "" : artifactBody;
        }

        public boolean passed() {
            return gates.stream().allMatch(GateResult::passed);
        }
    }
}
