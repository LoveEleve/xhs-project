package com.harnessrunner.engine;

import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.domain.change.ChangeEventType;
import com.harnessrunner.domain.change.ChangeStatus;
import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.engine.store.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeEngineTest {

    @TempDir
    Path tempDir;

    private EngineFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new EngineFixture(tempDir);
        writeCoverageReport(80, 20);
    }

    private void writeCoverageReport(int covered, int missed) throws IOException {
        Path report = tempDir.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <report name="demo">
                    <counter type="LINE" missed="%d" covered="%d"/>
                </report>
                """.formatted(missed, covered));
    }

    @Test
    void fullLifecycleReachesDoneWithApproval() {
        Change change = fixture.engine.create("proj-1", tempDir, "完善读写分离", 0.5);
        assertEquals(ChangeStatus.CREATED, change.status());

        AdvanceResult first = fixture.engine.advance(change.id());
        assertTrue(first.stageRun().passed());
        assertEquals(ChangeStatus.AWAITING_APPROVAL, first.change().status());
        assertEquals(Stage.HARNESSING, first.change().currentStage());
        assertTrue(first.message().contains("等待人工确认"));

        AdvanceResult refused = fixture.engine.advance(change.id());
        assertNull(refused.stageRun());
        assertTrue(refused.message().contains("请先 approve"));

        AdvanceResult approved = fixture.engine.approve(change.id());
        assertEquals(ChangeStatus.IN_PROGRESS, approved.change().status());
        assertEquals(Stage.CODING, approved.change().currentStage());

        for (int i = 0; i < 5; i++) {
            AdvanceResult result = fixture.engine.advance(change.id());
            assertTrue(result.stageRun().passed(), result.message());
        }

        Change done = fixture.engine.status(change.id());
        assertEquals(ChangeStatus.DONE, done.status());
        assertNull(done.currentStage());
        assertEquals(6, fixture.engine.runs(change.id()).size());
        assertEquals(6, fixture.engine.artifacts(change.id()).size());

        List<ChangeEvent> events = fixture.engine.events(change.id());
        assertEquals(ChangeEventType.CHANGE_CREATED, events.get(0).type());
        assertEquals(1, events.stream().filter(e -> e.type() == ChangeEventType.APPROVAL_REQUESTED).count());
        assertEquals(1, events.stream().filter(e -> e.type() == ChangeEventType.APPROVED).count());
        assertEquals(ChangeEventType.CHANGE_DONE, events.get(events.size() - 1).type());

        AdvanceResult terminal = fixture.engine.advance(change.id());
        assertNull(terminal.stageRun());
        assertTrue(terminal.message().contains("终态"), terminal.message());
    }

    @Test
    void approveRequiresAwaitingState() {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", null);

        assertThrows(IllegalStateException.class, () -> fixture.engine.approve(change.id()));
    }

    @Test
    void failedStageBlocksChangeAndRetryContinuesFromSameStage() {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", null);
        fixture.engine.advance(change.id());
        fixture.engine.approve(change.id());
        assertTrue(fixture.engine.advance(change.id()).stageRun().passed());

        fixture.executor.exitCode = 1;
        fixture.executor.output = "[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0";
        AdvanceResult failed = fixture.engine.advance(change.id());

        assertFalse(failed.stageRun().passed());
        Change failedState = fixture.engine.status(change.id());
        assertEquals(ChangeStatus.FAILED, failedState.status());
        assertEquals(Stage.TEST_WRITE, failedState.currentStage());

        fixture.executor.exitCode = 0;
        AdvanceResult retried = fixture.engine.advance(change.id());

        assertTrue(retried.stageRun().passed());
        assertEquals(2, retried.stageRun().attempt());
        Change afterRetry = fixture.engine.status(change.id());
        assertEquals(ChangeStatus.IN_PROGRESS, afterRetry.status());
        assertEquals(Stage.REVIEW, afterRetry.currentStage());
        assertTrue(fixture.engine.events(change.id()).stream()
                .anyMatch(event -> event.type() == ChangeEventType.CHANGE_FAILED));
    }

    @Test
    void llmFailureBlocksHarnessingAndKeepsPromptEvidence() {
        EngineFixture failing = new EngineFixture(tempDir, (system, user) -> {
            throw new com.harnessrunner.llm.LlmException("额度不足");
        });
        Change change = failing.engine.create("proj-1", tempDir, "需求", null);

        AdvanceResult result = failing.engine.advance(change.id());

        assertFalse(result.stageRun().passed());
        assertEquals(Stage.HARNESSING, result.stageRun().stage());
        Change failedState = failing.engine.status(change.id());
        assertEquals(ChangeStatus.FAILED, failedState.status());

        assertEquals(1, failing.llmCalls.findByChangeId(change.id()).size());
        assertEquals("REJECTED", failing.llmCalls.findByChangeId(change.id()).get(0).status().name());

        String prdPath = failing.engine.artifacts(change.id()).get(0).path();
        assertTrue(readString(Path.of(prdPath)).contains("生成失败"));
    }

    @Test
    void continuesAfterRestart() {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", null);
        fixture.engine.advance(change.id());
        assertEquals(ChangeStatus.AWAITING_APPROVAL, fixture.engine.status(change.id()).status());

        EngineFixture restarted = new EngineFixture(tempDir);
        AdvanceResult refused = restarted.engine.advance(change.id());
        assertNull(refused.stageRun());
        assertTrue(refused.message().contains("请先 approve"));

        restarted.engine.approve(change.id());
        AdvanceResult result = restarted.engine.advance(change.id());

        assertTrue(result.stageRun().passed(), result.message());
        assertEquals(Stage.TEST_WRITE, restarted.engine.status(change.id()).currentStage());
        assertEquals(2, restarted.engine.runs(change.id()).size());
        assertEquals(2, restarted.engine.artifacts(change.id()).size());
    }

    @Test
    void coverageThresholdBlocksCiStage() {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", 0.95);
        assertTrue(fixture.engine.advance(change.id()).stageRun().passed());
        fixture.engine.approve(change.id());
        for (int i = 0; i < 3; i++) {
            assertTrue(fixture.engine.advance(change.id()).stageRun().passed());
        }

        AdvanceResult ci = fixture.engine.advance(change.id());

        assertFalse(ci.stageRun().passed());
        assertEquals(Stage.CI, ci.stageRun().stage());
        Change failedState = fixture.engine.status(change.id());
        assertEquals(ChangeStatus.FAILED, failedState.status());
        assertEquals(Stage.CI, failedState.currentStage());
        assertTrue(fixture.engine.events(change.id()).stream()
                .anyMatch(event -> event.type() == ChangeEventType.STAGE_FAILED
                        && event.message().contains("COVERAGE=FAIL")));
    }

    @Test
    void approveRequiresArtifactEvidence() throws IOException {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", null);
        fixture.engine.advance(change.id());
        String artifactPath = fixture.engine.artifacts(change.id()).get(0).path();
        Files.delete(Path.of(artifactPath));

        assertThrows(IllegalStateException.class, () -> fixture.engine.approve(change.id()));
    }

    @Test
    void approvedEventRecordsApprovedStage() {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", null);
        fixture.engine.advance(change.id());

        fixture.engine.approve(change.id());

        ChangeEvent approved = fixture.engine.events(change.id()).stream()
                .filter(event -> event.type() == ChangeEventType.APPROVED)
                .findFirst()
                .orElseThrow();
        assertEquals(Stage.HARNESSING, approved.stage());
    }

    @Test
    void advanceFailsFastWhenChangeLockHeld() {
        Change change = fixture.engine.create("proj-1", tempDir, "需求", null);

        try (var handle = fixture.changeLocks.acquire(change.id())) {
            assertNotNull(handle);
            assertThrows(OptimisticLockException.class, () -> fixture.engine.advance(change.id()));
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
