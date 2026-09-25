package com.harnessrunner.engine;

import com.harnessrunner.domain.artifact.Artifact;
import com.harnessrunner.domain.artifact.ArtifactType;
import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.domain.change.ChangeEventType;
import com.harnessrunner.domain.change.ChangeStatus;
import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.engine.store.ArtifactStore;
import com.harnessrunner.engine.store.ChangeLocks;
import com.harnessrunner.engine.store.ChangeRecord;
import com.harnessrunner.engine.store.ChangeRepository;
import com.harnessrunner.engine.store.EventLog;
import com.harnessrunner.engine.store.StageRunRecord;
import com.harnessrunner.engine.store.StageRunRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ChangeEngine {

    private final ChangeRepository changeRepository;
    private final StageRunRepository stageRunRepository;
    private final EventLog eventLog;
    private final ArtifactStore artifactStore;
    private final StageExecutor stageExecutor;
    private final ChangeLocks changeLocks;

    public ChangeEngine(ChangeRepository changeRepository, StageRunRepository stageRunRepository,
                        EventLog eventLog, ArtifactStore artifactStore, StageExecutor stageExecutor,
                        ChangeLocks changeLocks) {
        this.changeRepository = changeRepository;
        this.stageRunRepository = stageRunRepository;
        this.eventLog = eventLog;
        this.artifactStore = artifactStore;
        this.stageExecutor = stageExecutor;
        this.changeLocks = changeLocks;
    }

    public Change create(String projectId, Path projectDir, String requirement, Double minLineCoverage) {
        Change change = new Change("chg-" + shortId(), projectId, requirement);
        changeRepository.save(ChangeRecord.of(change, projectDir.toString(), minLineCoverage));
        eventLog.append(ChangeEvent.of(change.id(), ChangeEventType.CHANGE_CREATED, null, requirement));
        return change;
    }

    public AdvanceResult advance(String changeId) {
        try (ChangeLocks.Handle ignored = changeLocks.acquire(changeId)) {
            return doAdvance(changeId);
        }
    }

    public AdvanceResult approve(String changeId) {
        try (ChangeLocks.Handle ignored = changeLocks.acquire(changeId)) {
            ChangeRecord persisted = require(changeId);
            Change change = persisted.toChange();
            if (change.status() != ChangeStatus.AWAITING_APPROVAL) {
                throw new IllegalStateException("当前状态无需审批: " + change.status().name());
            }
            ArtifactType approvalType = StageExecutor.artifactTypeFor(change.currentStage());
            if (artifactStore.latest(changeId, approvalType).isEmpty()) {
                throw new IllegalStateException("找不到待审批产物（" + approvalType.name()
                        + "），拒绝审批: " + changeId);
            }
            Stage approvedStage = change.currentStage();
            change.approve();
            save(change, persisted);
            eventLog.append(ChangeEvent.of(change.id(), ChangeEventType.APPROVED, approvedStage,
                    "人工确认通过"));
            return new AdvanceResult(change, null, "审批通过，当前阶段: " + change.currentStage().label());
        }
    }

    private AdvanceResult doAdvance(String changeId) {
        ChangeRecord persisted = require(changeId);
        Change change = persisted.toChange();

        if (change.status().isTerminal()) {
            return new AdvanceResult(change, null, "终态（" + change.status().name() + "），无需推进");
        }
        if (change.status() == ChangeStatus.AWAITING_APPROVAL) {
            return new AdvanceResult(change, null,
                    "等待人工确认（" + change.currentStage().label() + "），请先 approve");
        }
        if (change.status() == ChangeStatus.CREATED) {
            change.start();
        } else if (change.status() == ChangeStatus.FAILED) {
            change.retry();
        } else if (change.status() == ChangeStatus.PAUSED) {
            change.resume();
        }

        EngineConfig config = new EngineConfig(Path.of(persisted.projectDir()), persisted.minLineCoverage());
        StageRunRecord run = stageExecutor.execute(change, config);

        if (!run.passed()) {
            change.fail();
            save(change, persisted);
            eventLog.append(ChangeEvent.of(change.id(), ChangeEventType.CHANGE_FAILED, change.currentStage(),
                    "失败门禁: " + failedGates(run)));
            return new AdvanceResult(change, run, "阶段失败: " + run.stage().label());
        }

        if (requiresApproval(run.stage())) {
            change.awaitApproval();
            save(change, persisted);
            eventLog.append(ChangeEvent.of(change.id(), ChangeEventType.APPROVAL_REQUESTED, run.stage(),
                    "阶段通过，等待人工确认"));
            return new AdvanceResult(change, run, "阶段通过，等待人工确认（approve）: " + run.stage().label());
        }

        change.advance();
        save(change, persisted);
        if (change.status() == ChangeStatus.DONE) {
            eventLog.append(ChangeEvent.of(change.id(), ChangeEventType.CHANGE_DONE, null, "全部阶段通过"));
        }
        return new AdvanceResult(change, run, "阶段通过: " + run.stage().label());
    }

    public Change status(String changeId) {
        return require(changeId).toChange();
    }

    public Optional<ChangeRecord> find(String changeId) {
        return changeRepository.findById(changeId);
    }

    public List<StageRunRecord> runs(String changeId) {
        return stageRunRepository.findByChangeId(changeId);
    }

    public List<ChangeEvent> events(String changeId) {
        return eventLog.findByChangeId(changeId);
    }

    public List<Artifact> artifacts(String changeId) {
        return artifactStore.list(changeId);
    }

    private ChangeRecord require(String changeId) {
        return find(changeId).orElseThrow(() -> new IllegalArgumentException("变更不存在: " + changeId));
    }

    private void save(Change change, ChangeRecord previous) {
        changeRepository.save(ChangeRecord.of(change, previous.projectDir(), previous.minLineCoverage()));
    }

    private static boolean requiresApproval(Stage stage) {
        return stage == Stage.HARNESSING;
    }

    private static String failedGates(StageRunRecord run) {
        return run.gates().stream()
                .filter(gate -> !gate.passed())
                .map(gate -> gate.type().name() + "(" + gate.reason() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("无");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
