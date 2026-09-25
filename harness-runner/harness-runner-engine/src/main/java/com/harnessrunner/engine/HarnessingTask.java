package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.domain.gate.GateType;
import com.harnessrunner.engine.store.LlmCall;
import com.harnessrunner.engine.store.LlmCallStatus;
import com.harnessrunner.engine.store.LlmCallStore;
import com.harnessrunner.llm.AcGeneration;
import com.harnessrunner.llm.AcGenerationException;
import com.harnessrunner.llm.AcGenerator;
import com.harnessrunner.llm.AcValidator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HarnessingTask implements StageTask {

    private final AcGenerator generator;
    private final LlmCallStore llmCallStore;

    public HarnessingTask(AcGenerator generator, LlmCallStore llmCallStore) {
        this.generator = generator;
        this.llmCallStore = llmCallStore;
    }

    @Override
    public StageTaskOutcome run(Change change, EngineConfig config) {
        String callId = "llm-" + UUID.randomUUID().toString().substring(0, 8);
        long startedAt = System.nanoTime();
        try {
            AcGeneration generation = generator.generate(change.projectId(), change.requirement());
            List<String> problems = AcValidator.problems(generation.draft().criteria());
            boolean valid = problems.isEmpty();

            LlmCall call = new LlmCall(callId, change.id(), change.currentStage(),
                    generation.response().model(),
                    generation.prompt().length(), generation.response().content().length(),
                    generation.response().latencyMs(),
                    valid ? LlmCallStatus.OK : LlmCallStatus.REJECTED, Instant.now());
            llmCallStore.save(call, generation.prompt(), generation.response().content());

            String reason = valid
                    ? "AC 可测性校验通过（" + generation.draft().criteria().size() + " 条）"
                    : String.join("; ", problems);
            GateResult gate = new GateResult(GateType.AC_TESTABLE, valid, reason,
                    "llm:" + generation.response().model(), valid ? 0 : 1,
                    generation.response().latencyMs(), false,
                    excerpt(generation.response().content()), false, null,
                    Map.of("acCount", generation.draft().criteria().size()));
            return new StageTaskOutcome(List.of(gate), AcReport.markdown(change, generation.draft(), call));
        } catch (AcGenerationException e) {
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            LlmCall call = new LlmCall(callId, change.id(), change.currentStage(), "n/a",
                    e.prompt().length(), e.rawResponse().length(), latencyMs,
                    LlmCallStatus.REJECTED, Instant.now());
            llmCallStore.save(call, e.prompt(), e.rawResponse());

            GateResult gate = new GateResult(GateType.AC_TESTABLE, false, e.getMessage(),
                    "llm", 1, latencyMs, false, excerpt(e.rawResponse()), false, null, Map.of());
            return new StageTaskOutcome(List.of(gate),
                    AcReport.failure(change, e.getMessage(), e.rawResponse(), call));
        }
    }

    private static String excerpt(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }
}
