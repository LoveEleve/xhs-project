package com.harnessrunner.gate;

import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.domain.gate.GateType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class GateRunner {

    private static final AtomicLong LOG_SEQUENCE = new AtomicLong();

    private final CommandExecutor executor;
    private final Path logDirectory;
    private final DiffProvider diffProvider;
    private final SurefireOutputParser surefireParser = new SurefireOutputParser();
    private final JacocoReportParser jacocoParser = new JacocoReportParser();
    private final PitReportParser pitParser = new PitReportParser();
    private final UnifiedDiffParser diffParser = new UnifiedDiffParser();
    private final IncrementalCoverageEvaluator incrementalEvaluator = new IncrementalCoverageEvaluator();

    public GateRunner(CommandExecutor executor, Path logDirectory) {
        this(executor, logDirectory, new GitDiffProvider(executor));
    }

    public GateRunner(CommandExecutor executor, Path logDirectory, DiffProvider diffProvider) {
        this.executor = executor;
        this.logDirectory = logDirectory;
        this.diffProvider = diffProvider;
    }

    public GateResult run(GateSpec spec) {
        ProcessResult result = executor.execute(spec.command(), logFile(spec.type()));
        Map<String, Number> metrics = new LinkedHashMap<>();

        boolean passed = result.succeeded();
        String reason = "exitCode=" + result.exitCode();
        if (result.timedOut()) {
            reason = "超时（" + spec.command().timeout().toMillis() + " ms），已强杀进程树";
        }

        TestSummary testSummary = null;
        if (spec.type() == GateType.TEST || spec.type() == GateType.COVERAGE) {
            testSummary = surefireParser.parse(result.output()).orElse(null);
            if (testSummary != null) {
                metrics.put("testsRun", testSummary.testsRun());
                metrics.put("failures", testSummary.failures());
                metrics.put("errors", testSummary.errors());
                metrics.put("skipped", testSummary.skipped());
            }
        }

        if (spec.type() == GateType.TEST && !result.timedOut()) {
            String testReason = judgeTest(result.exitCode(), testSummary);
            if (testReason != null) {
                passed = false;
                reason = testReason;
            }
        }

        if (passed && spec.type() == GateType.COVERAGE) {
            Path report = coverageReport(spec.command());
            CoverageSummary coverage = jacocoParser.parse(report).orElse(null);
            if (coverage == null) {
                passed = false;
                reason = Files.isRegularFile(report)
                        ? "JaCoCo 报告无法解析: " + report
                        : "未找到 JaCoCo 报告: " + report;
            } else if (!coverage.hasLineData()) {
                passed = false;
                reason = "JaCoCo 报告无行覆盖率数据: " + report;
            } else {
                metrics.put("lineCovered", coverage.lineCovered());
                metrics.put("lineMissed", coverage.lineMissed());
                metrics.put("linePercent", round1(coverage.linePercent()));
                if (coverage.hasBranchData()) {
                    metrics.put("branchPercent", round1(coverage.branchPercent()));
                }
                if (spec.minScore() == null) {
                    reason = String.format("行覆盖率 %.1f%%", coverage.linePercent());
                } else if (coverage.lineRatio() < spec.minScore()) {
                    passed = false;
                    reason = String.format("行覆盖率 %.1f%% 低于阈值 %.1f%%",
                            coverage.linePercent(), spec.minScore() * 100.0);
                } else {
                    reason = String.format("行覆盖率 %.1f%% ≥ 阈值 %.1f%%",
                            coverage.linePercent(), spec.minScore() * 100.0);
                }
            }
        }

        if (passed && spec.type() == GateType.MUTATION) {
            Path report = pitReport(spec.command());
            MutationSummary mutation = pitParser.parse(report).orElse(null);
            if (mutation == null) {
                passed = false;
                reason = Files.isRegularFile(report)
                        ? "PIT 报告无法解析: " + report
                        : "未找到 PIT 报告: " + report;
            } else if (!mutation.hasData()) {
                passed = false;
                reason = "PIT 报告无变异数据: " + report;
            } else {
                metrics.put("killed", mutation.killed());
                metrics.put("survived", mutation.survived());
                metrics.put("noCoverage", mutation.noCoverage());
                metrics.put("mutationPercent", round1(mutation.percent()));
                String count = "（KILLED=" + mutation.killed() + ", SURVIVED=" + mutation.survived()
                        + ", NO_COVERAGE=" + mutation.noCoverage() + "）";
                if (spec.minScore() == null) {
                    reason = String.format("变异得分 %.1f%%%s", mutation.percent(), count);
                } else if (mutation.score() < spec.minScore()) {
                    passed = false;
                    reason = String.format("变异得分 %.1f%% 低于阈值 %.1f%%%s",
                            mutation.percent(), spec.minScore() * 100.0, count);
                } else {
                    reason = String.format("变异得分 %.1f%% ≥ 阈值 %.1f%%%s",
                            mutation.percent(), spec.minScore() * 100.0, count);
                }
            }
        }

        if (passed && spec.type() == GateType.DIFF_COVERAGE) {
            DiffOutcome outcome = evaluateDiffCoverage(spec);
            passed = outcome.passed();
            reason = outcome.reason();
            metrics.putAll(outcome.metrics());
        }

        return new GateResult(spec.type(), passed, reason, result.command(), result.exitCode(),
                result.durationMs(), result.timedOut(), result.output(), result.outputTruncated(),
                result.outputRef(), metrics);
    }

    private DiffOutcome evaluateDiffCoverage(GateSpec spec) {
        Path report = coverageReport(spec.command());
        if (!Files.isRegularFile(report)) {
            return new DiffOutcome(false, "未找到 JaCoCo 报告: " + report, Map.of());
        }
        Map<String, List<LineRange>> changedLines;
        try {
            String diffOutput = diffProvider.diff(spec.command().workingDir(), spec.diffBase());
            changedLines = diffParser.parse(diffOutput);
        } catch (RuntimeException e) {
            return new DiffOutcome(false, "获取 git diff 失败: " + e.getMessage(), Map.of());
        }

        IncrementalCoverage incremental = incrementalEvaluator.evaluate(report, changedLines);
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("changedExecutableLines", incremental.executableChangedLines());
        metrics.put("coveredChangedLines", incremental.coveredChangedLines());
        metrics.put("incrementalPercent", round1(incremental.percent()));
        metrics.put("changedFiles", changedLines.size());

        if (!incremental.hasExecutableChanges()) {
            return new DiffOutcome(true, "变更无可执行行，跳过增量覆盖率阈值（base=" + spec.diffBase() + "）",
                    metrics);
        }
        String counts = "（覆盖 " + incremental.coveredChangedLines() + "/"
                + incremental.executableChangedLines() + " 变更可执行行）";
        if (spec.minScore() == null) {
            return new DiffOutcome(true, String.format("增量覆盖率 %.1f%%%s", incremental.percent(), counts),
                    metrics);
        }
        if (incremental.ratio() < spec.minScore()) {
            return new DiffOutcome(false, String.format("增量覆盖率 %.1f%% 低于阈值 %.1f%%%s",
                    incremental.percent(), spec.minScore() * 100.0, counts), metrics);
        }
        return new DiffOutcome(true, String.format("增量覆盖率 %.1f%% ≥ 阈值 %.1f%%%s",
                incremental.percent(), spec.minScore() * 100.0, counts), metrics);
    }

    private record DiffOutcome(boolean passed, String reason, Map<String, Number> metrics) {
    }

    private static String judgeTest(int exitCode, TestSummary summary) {
        if (summary == null) {
            return "exitCode=" + exitCode + "，未解析到 surefire 汇总，测试证据缺失";
        }
        if (summary.testsRun() == 0) {
            return "exitCode=" + exitCode + "，0 个测试，测试证据不足";
        }
        if (summary.testsRun() - summary.skipped() <= 0) {
            return "exitCode=" + exitCode + "，测试全部被跳过（有效执行 0/" + summary.testsRun()
                    + "），测试证据不足";
        }
        if (exitCode != 0) {
            return "exitCode=" + exitCode + "（testsRun=" + summary.testsRun()
                    + ", failures=" + summary.failures() + ", errors=" + summary.errors() + "）";
        }
        return null;
    }

    private Path logFile(GateType type) {
        if (logDirectory == null) {
            return null;
        }
        return logDirectory.resolve(type.name().toLowerCase()
                + "-" + System.currentTimeMillis()
                + "-" + LOG_SEQUENCE.incrementAndGet() + ".log");
    }

    private static Path coverageReport(GateCommand command) {
        return command.workingDir().resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.xml");
    }

    private static Path pitReport(GateCommand command) {
        return command.workingDir().resolve("target").resolve("pit-reports").resolve("mutations.xml");
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
