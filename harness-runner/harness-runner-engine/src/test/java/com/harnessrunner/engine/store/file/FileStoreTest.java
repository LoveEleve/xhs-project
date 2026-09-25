package com.harnessrunner.engine.store.file;

import com.harnessrunner.domain.artifact.Artifact;
import com.harnessrunner.domain.artifact.ArtifactType;
import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.domain.change.ChangeEventType;
import com.harnessrunner.domain.change.Stage;
import com.harnessrunner.domain.change.StageRunStatus;
import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.domain.gate.GateType;
import com.harnessrunner.engine.EventChainVerifier;
import com.harnessrunner.engine.store.ChangeRecord;
import com.harnessrunner.engine.store.OptimisticLockException;
import com.harnessrunner.engine.store.StageRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStoreTest {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private JsonCodec codec;
    private FileChangeRepository changeRepository;
    private FileStageRunRepository stageRunRepository;
    private FileEventLog eventLog;
    private FileArtifactStore artifactStore;

    @BeforeEach
    void setUp() {
        workspace = new Workspace(tempDir.resolve("data"));
        codec = new JsonCodec();
        changeRepository = new FileChangeRepository(workspace, codec);
        stageRunRepository = new FileStageRunRepository(workspace, codec);
        eventLog = new FileEventLog(workspace, codec);
        artifactStore = new FileArtifactStore(workspace);
    }

    @Test
    void changeRoundTrip() {
        Change change = new Change("chg-1", "proj-1", "完善读写分离");
        change.start();
        change.advance();
        changeRepository.save(ChangeRecord.of(change, tempDir.toString(), 0.8));

        ChangeRecord loaded = changeRepository.findById("chg-1").orElseThrow();

        assertEquals("chg-1", loaded.id());
        assertEquals(change.status(), loaded.status());
        assertEquals(Stage.CODING, loaded.currentStage());
        assertEquals(0.8, loaded.minLineCoverage());
        assertEquals(tempDir.toString(), loaded.projectDir());
        assertEquals(change.version(), loaded.toChange().version());
    }

    @Test
    void missingChangeReturnsEmpty() {
        assertTrue(changeRepository.findById("chg-none").isEmpty());
    }

    @Test
    void stageRunAttemptsIncreasePerStage() {
        stageRunRepository.save(stageRun(Stage.CODING, 1, StageRunStatus.FAILED));
        stageRunRepository.save(stageRun(Stage.CODING, 2, StageRunStatus.PASSED));
        stageRunRepository.save(stageRun(Stage.TEST_WRITE, 1, StageRunStatus.PASSED));

        assertEquals(3, stageRunRepository.nextAttempt("chg-1", Stage.CODING));
        assertEquals(2, stageRunRepository.nextAttempt("chg-1", Stage.TEST_WRITE));
        assertEquals(1, stageRunRepository.nextAttempt("chg-1", Stage.REVIEW));
        assertEquals(3, stageRunRepository.findByChangeId("chg-1").size());
    }

    @Test
    void stageRunRoundTripKeepsGateEvidence() {
        stageRunRepository.save(stageRun(Stage.CODING, 1, StageRunStatus.PASSED));

        StageRunRecord loaded = stageRunRepository.findByChangeId("chg-1").get(0);

        assertEquals(1, loaded.gates().size());
        GateResult gate = loaded.gates().get(0);
        assertEquals(GateType.COMPILE, gate.type());
        assertTrue(gate.passed());
        assertEquals("exitCode=0", gate.reason());
        assertEquals(2, gate.metrics().get("testsRun").intValue());
        assertTrue(loaded.passed());
    }

    @Test
    void eventLogAppendsInOrder() {
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.CHANGE_CREATED, null, "创建"));
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_STARTED, Stage.HARNESSING, "attempt=1"));

        List<ChangeEvent> events = eventLog.findByChangeId("chg-1");

        assertEquals(2, events.size());
        assertEquals(ChangeEventType.CHANGE_CREATED, events.get(0).type());
        assertEquals(ChangeEventType.STAGE_STARTED, events.get(1).type());
        assertEquals(Stage.HARNESSING, events.get(1).stage());
    }

    @Test
    void eventLogToleratesTornLines() {
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.CHANGE_CREATED, null, "创建"));
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_STARTED, Stage.HARNESSING, "attempt=1"));

        Path file = workspace.eventsFile("chg-1");
        writeRaw(file, """
                {"changeId":"chg-1","type":"STAGE""");

        List<ChangeEvent> afterTorn = eventLog.findByChangeId("chg-1");
        assertEquals(2, afterTorn.size());

        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_PASSED, Stage.HARNESSING, "attempt=1"));
        List<ChangeEvent> afterRecovery = eventLog.findByChangeId("chg-1");
        assertEquals(3, afterRecovery.size());
        assertEquals(ChangeEventType.STAGE_PASSED, afterRecovery.get(2).type());
    }

    @Test
    void rejectsStaleVersionWrite() {
        Change change = new Change("chg-1", "proj-1", "需求");
        changeRepository.save(ChangeRecord.of(change, tempDir.toString(), null));

        ChangeRecord stale = ChangeRecord.of(change, tempDir.toString(), null);
        assertThrows(OptimisticLockException.class, () -> changeRepository.save(stale));

        change.start();
        changeRepository.save(ChangeRecord.of(change, tempDir.toString(), null));
        assertEquals(1, changeRepository.findById("chg-1").orElseThrow().version());
    }

    @Test
    void rejectsPathTraversalChangeId() {
        assertThrows(IllegalArgumentException.class, () -> changeRepository.findById("../../evil"));
        assertThrows(IllegalArgumentException.class, () -> stageRunRepository.findByChangeId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> artifactStore.list("a/b"));
        assertThrows(IllegalArgumentException.class, () -> eventLog.findByChangeId(".."));
    }

    @Test
    void writesLeaveNoTempFiles() throws Exception {
        Change change = new Change("chg-1", "proj-1", "需求");
        changeRepository.save(ChangeRecord.of(change, tempDir.toString(), null));
        stageRunRepository.save(stageRun(Stage.CODING, 1, StageRunStatus.PASSED));
        artifactStore.save("chg-1", ArtifactType.PRD, "内容");

        try (var files = Files.walk(workspace.changesDir())) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void eventChainIsIntactAfterAppends() {
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.CHANGE_CREATED, null, "创建"));
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_STARTED, Stage.HARNESSING, "attempt=1"));
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_PASSED, Stage.HARNESSING, "TEST=PASS"));

        EventChainVerifier.ChainVerification verification = new EventChainVerifier(eventLog).verify("chg-1");

        assertTrue(verification.intact());
        assertEquals(3, verification.checkedEvents());
        assertEquals(0, verification.legacyEvents());
    }

    @Test
    void eventChainDetectsTampering() throws IOException {
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.CHANGE_CREATED, null, "创建"));
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_STARTED, Stage.HARNESSING, "attempt=1"));
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_PASSED, Stage.HARNESSING, "TEST=PASS"));
        Path file = workspace.eventsFile("chg-1");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("attempt=1", "attempt=9"), StandardCharsets.UTF_8);

        EventChainVerifier.ChainVerification verification = new EventChainVerifier(eventLog).verify("chg-1");

        assertFalse(verification.intact());
        assertTrue(verification.detail().contains("疑似篡改"), verification.detail());
    }

    @Test
    void eventChainSkipsLegacyLines() throws IOException {
        Path file = workspace.eventsFile("chg-1");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"changeId":"chg-1","type":"CHANGE_CREATED","message":"历史事件","occurredAt":"2026-09-24T10:00:00Z"}
                """, StandardCharsets.UTF_8);
        eventLog.append(ChangeEvent.of("chg-1", ChangeEventType.STAGE_STARTED, Stage.HARNESSING, "attempt=1"));

        EventChainVerifier.ChainVerification verification = new EventChainVerifier(eventLog).verify("chg-1");

        assertTrue(verification.intact());
        assertEquals(1, verification.checkedEvents());
        assertEquals(1, verification.legacyEvents());
    }

    private static void writeRaw(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void artifactVersionsIncreaseAndContentPersists() throws Exception {
        Artifact first = artifactStore.save("chg-1", ArtifactType.PRD, "第一版");
        Artifact second = artifactStore.save("chg-1", ArtifactType.PRD, "第二版");

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals("第一版", Files.readString(Path.of(first.path())));
        assertEquals(second, artifactStore.latest("chg-1", ArtifactType.PRD).orElseThrow());
        assertEquals(2, artifactStore.list("chg-1").size());
        assertEquals(64, first.sha256().length());
    }

    private StageRunRecord stageRun(Stage stage, int attempt, StageRunStatus status) {
        GateResult gate = new GateResult(GateType.COMPILE, true, "exitCode=0", "mvn compile", 0,
                120L, false, "输出", false, null, Map.of("testsRun", 2));
        return new StageRunRecord("run-" + stage + "-" + attempt, "chg-1", stage, attempt, status,
                Instant.parse("2026-09-24T10:00:00Z"), Instant.parse("2026-09-24T10:00:01Z"), List.of(gate));
    }
}
