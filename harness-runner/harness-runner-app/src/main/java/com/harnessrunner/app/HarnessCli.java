package com.harnessrunner.app;

import com.harnessrunner.domain.artifact.Artifact;
import com.harnessrunner.domain.change.Change;
import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.domain.change.ChangeStatus;
import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.engine.AdvanceResult;
import com.harnessrunner.engine.ChangeEngine;
import com.harnessrunner.engine.EventChainVerifier;
import com.harnessrunner.engine.HarnessingTask;
import com.harnessrunner.engine.ProcessGateTask;
import com.harnessrunner.engine.StageExecutor;
import com.harnessrunner.engine.StageTask;
import com.harnessrunner.engine.store.LlmCall;
import com.harnessrunner.engine.store.StageRunRecord;
import com.harnessrunner.engine.store.file.FileArtifactStore;
import com.harnessrunner.engine.store.file.FileChangeLocks;
import com.harnessrunner.engine.store.file.FileChangeRepository;
import com.harnessrunner.engine.store.file.FileEventLog;
import com.harnessrunner.engine.store.file.FileLlmCallStore;
import com.harnessrunner.engine.store.file.FileStageRunRepository;
import com.harnessrunner.engine.store.file.JsonCodec;
import com.harnessrunner.engine.store.file.Workspace;
import com.harnessrunner.gate.GateRunner;
import com.harnessrunner.gate.ProcessExecutor;
import com.harnessrunner.llm.AcGenerator;
import com.harnessrunner.llm.FakeLlmClient;
import com.harnessrunner.llm.LlmClient;
import com.harnessrunner.llm.LlmConfig;
import com.harnessrunner.llm.OpenAiCompatibleClient;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HarnessCli {

    private static final Pattern CHANGE_ID = Pattern.compile("(chg-[0-9a-f]{8})");

    public static void main(String[] args) {
        System.exit(new HarnessCli().run(args, System.out));
    }

    int run(String[] args, PrintStream out) {
        if (args.length == 0 || args[0].startsWith("--")) {
            usage(out);
            return 2;
        }
        Map<String, String> options = parseOptions(args);
        try {
            return switch (args[0]) {
                case "create" -> create(options, out);
                case "advance" -> advance(options, out);
                case "approve" -> approve(options, out);
                case "verify" -> verify(options, out);
                case "status" -> status(options, out);
                default -> {
                    usage(out);
                    yield 2;
                }
            };
        } catch (UsageException e) {
            out.println("错误: " + e.getMessage());
            usage(out);
            return 2;
        } catch (IllegalStateException e) {
            out.println("错误: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            out.println("错误: " + e.getMessage());
            return 1;
        }
    }

    static String extractChangeId(String output) {
        Matcher matcher = CHANGE_ID.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("输出中未找到变更 ID: " + output);
        }
        return matcher.group(1);
    }

    private int create(Map<String, String> options, PrintStream out) {
        String requirement = required(options, "requirement");
        Path projectDir = Path.of(options.getOrDefault("project", System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        String projectId = options.getOrDefault("project-id", String.valueOf(projectDir.getFileName()));
        Double minCoverage = options.containsKey("min-coverage")
                ? Double.parseDouble(options.get("min-coverage"))
                : null;
        Path workspace = workspace(options);
        Runtime runtime = runtime(workspace);

        Change change = runtime.engine().create(projectId, projectDir, requirement, minCoverage);

        out.println("已创建变更 " + change.id() + "（项目: " + projectId + "）");
        out.println("项目目录: " + projectDir);
        out.println("工作区:   " + workspace);
        out.println("LLM:      " + runtime.llmMode());
        return 0;
    }

    private int advance(Map<String, String> options, PrintStream out) {
        String changeId = required(options, "change");
        AdvanceResult result = runtime(workspace(options)).engine().advance(changeId);

        printStageRun(out, result.stageRun());
        if (result.stageRun() == null || result.change().status() == ChangeStatus.AWAITING_APPROVAL) {
            out.println("说明: " + result.message());
        }
        Change change = result.change();
        out.println("状态: " + change.status().name()
                + (change.currentStage() == null ? "" : " · 当前阶段 " + change.currentStage().label()));
        if (change.status() == ChangeStatus.AWAITING_APPROVAL) {
            return 3;
        }
        return result.stageRun() != null && !result.stageRun().passed() ? 1 : 0;
    }

    private int approve(Map<String, String> options, PrintStream out) {
        String changeId = required(options, "change");
        AdvanceResult result = runtime(workspace(options)).engine().approve(changeId);

        out.println(result.message());
        Change change = result.change();
        out.println("状态: " + change.status().name() + " · 当前阶段 " + change.currentStage().label());
        return 0;
    }

    private int verify(Map<String, String> options, PrintStream out) {
        String changeId = required(options, "change");
        EventChainVerifier.ChainVerification verification =
                runtime(workspace(options)).verifier().verify(changeId);

        out.println("变更: " + changeId);
        out.println("事件链: " + (verification.intact() ? "完整" : "断裂"));
        out.println("  " + verification.detail());
        return verification.intact() ? 0 : 1;
    }

    private int status(Map<String, String> options, PrintStream out) {
        String changeId = required(options, "change");
        Runtime runtime = runtime(workspace(options));
        ChangeEngine engine = runtime.engine();
        Change change = engine.status(changeId);

        out.println("变更: " + change.id() + "   状态: " + change.status().name()
                + "   当前阶段: " + (change.currentStage() == null ? "-" : change.currentStage().label())
                + "   版本: " + change.version());
        out.println("需求: " + change.requirement());
        engine.find(changeId).ifPresent(record -> out.println("项目目录: " + record.projectDir()));

        out.println();
        out.println("阶段运行:");
        for (StageRunRecord run : engine.runs(changeId)) {
            out.printf("  %-14s #%-2d %-4s %s%n", run.stage().name(), run.attempt(),
                    run.passed() ? "PASS" : "FAIL", run.finishedAt());
        }

        out.println();
        out.println("产物:");
        for (Artifact artifact : engine.artifacts(changeId)) {
            out.printf("  %-12s v%-2d %s%n", artifact.type().name(), artifact.version(), artifact.path());
        }

        List<LlmCall> llmCalls = runtime.llmCalls().findByChangeId(changeId);
        if (!llmCalls.isEmpty()) {
            out.println();
            out.println("LLM 调用:");
            for (LlmCall call : llmCalls) {
                out.printf("  %s  %-18s %-8s %d ms  in/out=%d/%d%n", call.id(), call.model(),
                        call.status(), call.latencyMs(), call.promptChars(), call.outputChars());
            }
        }

        out.println();
        out.println("最近事件:");
        List<ChangeEvent> events = engine.events(changeId);
        events.stream().skip(Math.max(0, events.size() - 5)).forEach(event ->
                out.printf("  %s  %-14s %-12s %s%n", event.occurredAt(), event.type().name(),
                        event.stage() == null ? "-" : event.stage().name(), event.message()));
        return 0;
    }

    private void printStageRun(PrintStream out, StageRunRecord run) {
        if (run == null) {
            out.println("（本次未执行阶段）");
            return;
        }
        out.println("阶段: " + run.stage().label() + "（第 " + run.attempt() + " 次尝试）");
        if (run.gates().isEmpty()) {
            out.println("  门禁: 无可执行门禁（人工/LLM 阶段）");
        }
        for (GateResult gate : run.gates()) {
            out.printf("  [%s] %-10s %s（%d ms）%n",
                    gate.passed() ? "PASS" : "FAIL",
                    gate.type().name(),
                    gate.reason(),
                    gate.durationMs());
        }
        out.println("结论: " + (run.passed() ? "通过" : "失败") + " · 产物: "
                + StageExecutor.artifactTypeFor(run.stage()).label());
    }

    private record Runtime(ChangeEngine engine, FileLlmCallStore llmCalls, EventChainVerifier verifier,
                           String llmMode) {
    }

    private static Runtime runtime(Path workspaceDir) {
        LlmClient client = LlmConfig.fromEnvironment()
                .<LlmClient>map(OpenAiCompatibleClient::new)
                .orElseGet(FakeLlmClient::new);
        String llmMode = client instanceof FakeLlmClient
                ? "fake（未配置 HARNESS_LLM_API_KEY；保留真实 prompt 记录）"
                : "OpenAI 兼容端点（模型来自 HARNESS_LLM_MODEL）";

        Workspace workspace = new Workspace(workspaceDir);
        JsonCodec codec = new JsonCodec();
        FileChangeRepository changes = new FileChangeRepository(workspace, codec);
        FileStageRunRepository runs = new FileStageRunRepository(workspace, codec);
        FileEventLog events = new FileEventLog(workspace, codec);
        FileArtifactStore artifacts = new FileArtifactStore(workspace);
        FileLlmCallStore llmCalls = new FileLlmCallStore(workspace, codec);
        GateRunner gateRunner = new GateRunner(new ProcessExecutor(), workspace.root().resolve("logs"));
        StageTask processTask = new ProcessGateTask(gateRunner);
        StageTask harnessingTask = new HarnessingTask(new AcGenerator(client), llmCalls);
        ChangeEngine engine = new ChangeEngine(changes, runs, events, artifacts,
                new StageExecutor(processTask, harnessingTask, runs, events, artifacts),
                new FileChangeLocks(workspace));
        return new Runtime(engine, llmCalls, new EventChainVerifier(events), llmMode);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new UsageException("未知参数: " + token);
            }
            String key = token.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new UsageException("参数 --" + key + " 缺少值");
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new UsageException("缺少必填参数 --" + key);
        }
        return value;
    }

    private static Path workspace(Map<String, String> options) {
        String dir = options.getOrDefault("workspace",
                Path.of(System.getProperty("user.dir"), ".harness-runner").toString());
        return Path.of(dir).toAbsolutePath().normalize();
    }

    private static void usage(PrintStream out) {
        out.println("harness-runner — AI 研发流水线引擎（CLI 版）");
        out.println();
        out.println("用法:");
        out.println("  create  --requirement <需求> [--project-id <id>] [--project <目录>] [--min-coverage <0-1>] [--workspace <目录>]");
        out.println("  advance --change <变更ID> [--workspace <目录>]");
        out.println("  approve --change <变更ID> [--workspace <目录>]");
        out.println("  verify  --change <变更ID> [--workspace <目录>]");
        out.println("  status  --change <变更ID> [--workspace <目录>]");
        out.println();
        out.println("默认: --project 为当前目录；--workspace 为当前目录/.harness-runner");
        out.println("LLM:  配置 HARNESS_LLM_API_KEY / HARNESS_LLM_BASE_URL / HARNESS_LLM_MODEL 走真实端点，否则用 fake");
        out.println("退出码: 0 成功 / 1 失败或事件链断裂 / 2 用法错误 / 3 等待人工确认（approve）");
    }

    private static final class UsageException extends RuntimeException {

        private UsageException(String message) {
            super(message);
        }
    }
}
