package com.harnessrunner.engine;

import com.harnessrunner.domain.artifact.ArtifactType;
import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.domain.change.ChangeEventType;
import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.domain.change.StageRun;
import com.harnessrunner.engine.store.ArtifactStore;
import com.harnessrunner.engine.store.EventLog;
import com.harnessrunner.engine.store.StageRunRecord;
import com.harnessrunner.engine.store.StageRunRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

public final class StageExecutor {

    private final StageTask processTask;
    private final StageTask harnessingTask;
    private final StageRunRepository stageRunRepository;
    private final EventLog eventLog;
    private final ArtifactStore artifactStore;

    public StageExecutor(StageTask processTask, StageTask harnessingTask,
                         StageRunRepository stageRunRepository, EventLog eventLog,
                         ArtifactStore artifactStore) {
        this.processTask = processTask;
        this.harnessingTask = harnessingTask;
        this.stageRunRepository = stageRunRepository;
        this.eventLog = eventLog;
        this.artifactStore = artifactStore;
    }

    public StageRunRecord execute(Change change, EngineConfig config) {
        Stage stage = change.currentStage();
        int attempt = stageRunRepository.nextAttempt(change.id(), stage);
        Instant startedAt = Instant.now();
        eventLog.append(ChangeEvent.of(change.id(), ChangeEventType.STAGE_STARTED, stage,
                "attempt=" + attempt));

        StageRun run = new StageRun("run-" + shortId(), change.id(), stage, attempt);
        run.markRunning();

        StageTask task = stage == Stage.HARNESSING ? harnessingTask : processTask;
        StageTask.StageTaskOutcome outcome = task.run(change, config);
        if (outcome.passed()) {
            run.markPassed();
        } else {
            run.markFailed();
        }

        StageRunRecord record = new StageRunRecord(run.id(), change.id(), stage, attempt, run.status(),
                startedAt, Instant.now(), outcome.gates());
        stageRunRepository.save(record);

        String report = StageReports.markdown(change, record);
        String content = outcome.artifactBody().isBlank()
                ? report
                : outcome.artifactBody() + "\n---\n\n" + report;
        artifactStore.save(change.id(), artifactTypeFor(stage), content);

        eventLog.append(ChangeEvent.of(change.id(),
                record.passed() ? ChangeEventType.STAGE_PASSED : ChangeEventType.STAGE_FAILED,
                stage, summary(record)));
        return record;
    }

    public static ArtifactType artifactTypeFor(Stage stage) {
        return switch (stage) {
            case HARNESSING -> ArtifactType.PRD;
            case CODING -> ArtifactType.SOLUTION;
            case TEST_WRITE -> ArtifactType.TEST_DESIGN;
            case REVIEW -> ArtifactType.REVIEW;
            case CI -> ArtifactType.TEST_REPORT;
            case DEPLOY_VERIFY -> ArtifactType.VERIFY;
        };
    }

    private static String summary(StageRunRecord record) {
        if (record.gates().isEmpty()) {
            return "无可执行门禁";
        }
        return record.gates().stream()
                .map(gate -> gate.type().name() + "=" + (gate.passed() ? "PASS" : "FAIL"))
                .collect(Collectors.joining(", "));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
