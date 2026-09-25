package com.harnessrunner.engine;

import com.harnessrunner.engine.store.file.FileArtifactStore;
import com.harnessrunner.engine.store.file.FileChangeLocks;
import com.harnessrunner.engine.store.file.FileChangeRepository;
import com.harnessrunner.engine.store.file.FileEventLog;
import com.harnessrunner.engine.store.file.FileLlmCallStore;
import com.harnessrunner.engine.store.file.FileStageRunRepository;
import com.harnessrunner.engine.store.file.JsonCodec;
import com.harnessrunner.engine.store.file.Workspace;
import com.harnessrunner.gate.CommandExecutor;
import com.harnessrunner.gate.GateCommand;
import com.harnessrunner.gate.GateRunner;
import com.harnessrunner.gate.ProcessResult;
import com.harnessrunner.llm.AcGenerator;
import com.harnessrunner.llm.FakeLlmClient;
import com.harnessrunner.llm.LlmClient;

import java.nio.file.Path;
import java.time.Duration;

final class EngineFixture {

    final Path projectDir;
    final FileChangeRepository changes;
    final FileStageRunRepository runs;
    final FileEventLog events;
    final FileArtifactStore artifacts;
    final FileLlmCallStore llmCalls;
    final FileChangeLocks changeLocks;
    final FakeCommandExecutor executor = new FakeCommandExecutor();
    final StageExecutor stageExecutor;
    final ChangeEngine engine;

    EngineFixture(Path projectDir) {
        this(projectDir, new FakeLlmClient());
    }

    EngineFixture(Path projectDir, LlmClient llmClient) {
        this.projectDir = projectDir;
        Workspace workspace = new Workspace(projectDir.resolve("harness-data"));
        JsonCodec codec = new JsonCodec();
        this.changes = new FileChangeRepository(workspace, codec);
        this.runs = new FileStageRunRepository(workspace, codec);
        this.events = new FileEventLog(workspace, codec);
        this.artifacts = new FileArtifactStore(workspace);
        this.llmCalls = new FileLlmCallStore(workspace, codec);
        this.changeLocks = new FileChangeLocks(workspace, Duration.ofMillis(50));
        GateRunner gateRunner = new GateRunner(executor, workspace.root().resolve("logs"));
        StageTask processTask = new ProcessGateTask(gateRunner);
        StageTask harnessingTask = new HarnessingTask(new AcGenerator(llmClient), llmCalls);
        this.stageExecutor = new StageExecutor(processTask, harnessingTask, runs, events, artifacts);
        this.engine = new ChangeEngine(changes, runs, events, artifacts, stageExecutor, changeLocks);
    }

    static final class FakeCommandExecutor implements CommandExecutor {

        int exitCode = 0;
        String output = "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0";

        @Override
        public ProcessResult execute(GateCommand command, Path logFile) {
            return new ProcessResult(command.display(), exitCode, false, 5L, output, false, null);
        }
    }
}
