package com.harnessrunner.engine;

import com.harnessrunner.domain.artifact.Artifact;
import com.harnessrunner.domain.artifact.ArtifactType;
import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeEventType;
import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.domain.change.StageRunStatus;
import com.harnessrunner.engine.store.StageRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageExecutorTest {

    @TempDir
    Path tempDir;

    private EngineFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new EngineFixture(tempDir);
    }

    private Change changeAt(Stage stage) {
        Change change = new Change("chg-x", "proj-x", "需求");
        change.start();
        while (change.currentStage() != stage) {
            change.advance();
        }
        return change;
    }

    @Test
    void passingTestStageWritesRecordArtifactAndEvents() throws IOException {
        Change change = changeAt(Stage.TEST_WRITE);

        StageRunRecord record = fixture.stageExecutor.execute(change, EngineConfig.of(tempDir));

        assertTrue(record.passed());
        assertEquals(StageRunStatus.PASSED, record.status());
        assertEquals(1, record.attempt());
        assertEquals(1, record.gates().size());
        assertEquals(1, fixture.runs.findByChangeId("chg-x").size());

        List<Artifact> artifacts = fixture.artifacts.list("chg-x");
        assertEquals(1, artifacts.size());
        assertEquals("TEST_DESIGN", artifacts.get(0).type().name());
        assertTrue(Files.readString(Path.of(artifacts.get(0).path())).contains("## 门禁结果"));

        assertEquals(ChangeEventType.STAGE_STARTED, fixture.events.findByChangeId("chg-x").get(0).type());
        assertEquals(ChangeEventType.STAGE_PASSED, fixture.events.findByChangeId("chg-x").get(1).type());
    }

    @Test
    void failingStageMarksFailedAndStillKeepsEvidence() {
        fixture.executor.exitCode = 1;
        fixture.executor.output = "[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0";
        Change change = changeAt(Stage.TEST_WRITE);

        StageRunRecord record = fixture.stageExecutor.execute(change, EngineConfig.of(tempDir));

        assertFalse(record.passed());
        assertEquals(StageRunStatus.FAILED, record.status());
        assertEquals(1, fixture.artifacts.list("chg-x").size());
        assertEquals(ChangeEventType.STAGE_FAILED, fixture.events.findByChangeId("chg-x").get(1).type());
    }

    @Test
    void attemptIncrementsAcrossExecutions() {
        Change change = changeAt(Stage.TEST_WRITE);
        fixture.executor.exitCode = 1;
        fixture.stageExecutor.execute(change, EngineConfig.of(tempDir));

        fixture.executor.exitCode = 0;
        StageRunRecord second = fixture.stageExecutor.execute(change, EngineConfig.of(tempDir));

        assertEquals(2, second.attempt());
        assertEquals(2, fixture.runs.findByChangeId("chg-x").size());
    }

    @Test
    void harnessingStageGeneratesAcArtifactAndLlmCall() throws IOException {
        Change change = changeAt(Stage.HARNESSING);

        StageRunRecord record = fixture.stageExecutor.execute(change, EngineConfig.of(tempDir));

        assertTrue(record.passed());
        assertEquals(1, record.gates().size());
        assertEquals("AC_TESTABLE", record.gates().get(0).type().name());
        assertEquals(3, record.gates().get(0).metric("acCount").intValue());

        Artifact prd = fixture.artifacts.latest("chg-x", ArtifactType.PRD).orElseThrow();
        String content = Files.readString(Path.of(prd.path()));
        assertTrue(content.contains("验收条件"), content);
        assertTrue(content.contains("AC-1"), content);
        assertTrue(content.contains("## 门禁结果"), content);

        assertEquals(1, fixture.llmCalls.findByChangeId("chg-x").size());
        assertEquals("OK", fixture.llmCalls.findByChangeId("chg-x").get(0).status().name());
        assertTrue(Files.isRegularFile(fixture.projectDir.resolve("harness-data/changes/chg-x/llm")
                .resolve(fixture.llmCalls.findByChangeId("chg-x").get(0).id() + "-prompt.txt")));
    }
}
