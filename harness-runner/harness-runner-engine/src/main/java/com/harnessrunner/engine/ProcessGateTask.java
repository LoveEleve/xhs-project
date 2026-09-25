package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.gate.GateRunner;
import com.harnessrunner.gate.GateSpec;

import java.util.ArrayList;
import java.util.List;

public final class ProcessGateTask implements StageTask {

    private final GateRunner gateRunner;

    public ProcessGateTask(GateRunner gateRunner) {
        this.gateRunner = gateRunner;
    }

    @Override
    public StageTaskOutcome run(Change change, EngineConfig config) {
        List<GateResult> gates = new ArrayList<>();
        for (GateSpec spec : StageGates.gatesFor(change.currentStage(), config)) {
            gates.add(gateRunner.run(spec));
        }
        return new StageTaskOutcome(gates, "");
    }
}
