package com.harnessrunner.gate;

import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.domain.gate.GateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateRunnerTest {

    private static final class FakeExecutor implements CommandExecutor {

        private ProcessResult result;
        private GateCommand command;
        private Path logFile;

        @Override
        public ProcessResult execute(GateCommand command, Path logFile) {
            this.command = command;
            this.logFile = logFile;
            return result;
        }
    }

    @TempDir
    Path tempDir;

    private final FakeExecutor executor = new FakeExecutor();

    private GateCommand command() {
        return GateCommand.of(List.of("mvn", "-B", "-ntp", "test"), tempDir, Duration.ofMinutes(1));
    }

    private static ProcessResult result(int exitCode, String output) {
        return new ProcessResult("mvn -B -ntp test", exitCode, false, 1234L, output, false, null);
    }

    private void writeCoverageReport(int covered, int missed) throws IOException {
        Path report = tempDir.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="demo">
                    <counter type="LINE" missed="%d" covered="%d"/>
                </report>
                """.formatted(missed, covered), StandardCharsets.UTF_8);
    }

    private void writePitReport(int killed, int survived, int noCoverage) throws IOException {
        Path report = tempDir.resolve("target/pit-reports/mutations.xml");
        Files.createDirectories(report.getParent());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n");
        appendMutations(xml, killed, "KILLED");
        appendMutations(xml, survived, "SURVIVED");
        appendMutations(xml, noCoverage, "NO_COVERAGE");
        xml.append("</mutations>\n");
        Files.writeString(report, xml.toString(), StandardCharsets.UTF_8);
    }

    private static void appendMutations(StringBuilder xml, int count, String status) {
        for (int i = 0; i < count; i++) {
            xml.append("<mutation detected='").append("KILLED".equals(status)).append("' status='")
                    .append(status).append("'><mutatedClass>demo.Calc</mutatedClass></mutation>\n");
        }
    }

    @Test
    void testGatePassesAndExtractsMetrics() {
        executor.result = result(0, "[INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0");
        GateRunner runner = new GateRunner(executor, tempDir.resolve("logs"));

        GateResult gate = runner.run(GateSpec.of(GateType.TEST, command()));

        assertTrue(gate.passed());
        assertEquals("exitCode=0", gate.reason());
        assertEquals(93, gate.metric("testsRun").intValue());
        assertEquals(0, gate.metric("failures").intValue());
        assertNotNull(executor.logFile);
        assertTrue(executor.logFile.startsWith(tempDir.resolve("logs")), executor.logFile.toString());
    }

    @Test
    void nonZeroExitCodeFailsGate() {
        executor.result = result(1, "[ERROR] Tests run: 10, Failures: 2, Errors: 1, Skipped: 0");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.of(GateType.TEST, command()));

        assertFalse(gate.passed());
        assertEquals(10, gate.metric("testsRun").intValue());
        assertTrue(gate.reason().contains("exitCode=1"), gate.reason());
        assertTrue(gate.reason().contains("failures=2"), gate.reason());
        assertTrue(gate.reason().contains("errors=1"), gate.reason());
        assertNull(executor.logFile);
    }

    @Test
    void testGateFailsWhenSummaryMissingEvenWithExitZero() {
        executor.result = result(0, "[INFO] BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.of(GateType.TEST, command()));

        assertFalse(gate.passed(), "exitCode=0 但无测试证据时必须红（防假绿）");
        assertTrue(gate.reason().contains("测试证据缺失"), gate.reason());
    }

    @Test
    void testGateFailsWhenZeroTestsRan() {
        executor.result = result(0, "[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.of(GateType.TEST, command()));

        assertFalse(gate.passed(), "0 个测试不能算通过");
        assertTrue(gate.reason().contains("0 个测试"), gate.reason());
    }

    @Test
    void testGateFailsWhenAllTestsSkipped() {
        executor.result = result(0, "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.of(GateType.TEST, command()));

        assertFalse(gate.passed(), "全部测试被跳过不能算通过（假绿变体）");
        assertTrue(gate.reason().contains("全部被跳过"), gate.reason());
    }

    @Test
    void timeoutFailsGateWithKillReason() {
        executor.result = new ProcessResult("mvn -B -ntp test", -1, true, 3000L, "", false, null);
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.of(GateType.TEST, command()));

        assertFalse(gate.passed());
        assertTrue(gate.timedOut());
        assertTrue(gate.reason().contains("超时"), gate.reason());
    }

    private void writeJacocoLinesReport() throws IOException {
        Path report = tempDir.resolve("target/site/jacoco/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <report name="demo">
                    <package name="demo">
                        <sourcefile name="Calc.java">
                            <line nr="5" mi="0" ci="2"/>
                            <line nr="6" mi="2" ci="0"/>
                        </sourcefile>
                    </package>
                    <counter type="LINE" missed="1" covered="1"/>
                </report>
                """, StandardCharsets.UTF_8);
    }

    private static final String DIFF_COVERING_LINE_5_AND_6 = """
            diff --git a/src/main/java/demo/Calc.java b/src/main/java/demo/Calc.java
            --- a/src/main/java/demo/Calc.java
            +++ b/src/main/java/demo/Calc.java
            @@ -5,2 +5,2 @@
            """;

    private GateRunner diffRunner(String diffText) {
        return new GateRunner(executor, null, (workingDir, baseRef) -> diffText);
    }

    @Test
    void diffCoveragePassesAtThreshold() throws IOException {
        writeJacocoLinesReport();
        executor.result = result(0, "BUILD SUCCESS");

        GateResult gate = diffRunner(DIFF_COVERING_LINE_5_AND_6)
                .run(GateSpec.diffCoverage(command(), "HEAD", 0.5));

        assertTrue(gate.passed(), gate.reason());
        assertEquals(50.0, gate.metric("incrementalPercent").doubleValue());
        assertEquals(2, gate.metric("changedExecutableLines").intValue());
        assertEquals(1, gate.metric("coveredChangedLines").intValue());
        assertTrue(gate.reason().contains("≥ 阈值"), gate.reason());
    }

    @Test
    void diffCoverageFailsBelowThreshold() throws IOException {
        writeJacocoLinesReport();
        executor.result = result(0, "BUILD SUCCESS");

        GateResult gate = diffRunner(DIFF_COVERING_LINE_5_AND_6)
                .run(GateSpec.diffCoverage(command(), "HEAD", 0.8));

        assertFalse(gate.passed());
        assertTrue(gate.reason().contains("低于阈值"), gate.reason());
        assertTrue(gate.reason().contains("覆盖 1/2"), gate.reason());
    }

    @Test
    void diffCoverageSkipsWhenNoExecutableChanges() throws IOException {
        writeJacocoLinesReport();
        executor.result = result(0, "BUILD SUCCESS");
        String commentOnlyDiff = DIFF_COVERING_LINE_5_AND_6.replace("+5,2", "+100,2");

        GateResult gate = diffRunner(commentOnlyDiff)
                .run(GateSpec.diffCoverage(command(), "HEAD", 0.8));

        assertTrue(gate.passed(), gate.reason());
        assertTrue(gate.reason().contains("无可执行行"), gate.reason());
    }

    @Test
    void diffCoverageFailsWhenGitDiffFails() throws IOException {
        writeJacocoLinesReport();
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null, (workingDir, baseRef) -> {
            throw new IllegalStateException("not a git repository");
        });

        GateResult gate = runner.run(GateSpec.diffCoverage(command(), "HEAD", 0.8));

        assertFalse(gate.passed());
        assertTrue(gate.reason().contains("获取 git diff 失败"), gate.reason());
    }

    @Test
    void coverageGatePassesAtThreshold() throws IOException {
        writeCoverageReport(80, 20);
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.coverage(command(), 0.8));

        assertTrue(gate.passed(), gate.reason());
        assertEquals(80.0, gate.metric("linePercent").doubleValue());
        assertTrue(gate.reason().contains("≥ 阈值"), gate.reason());
    }

    @Test
    void coverageGateFailsBelowThreshold() throws IOException {
        writeCoverageReport(75, 25);
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.coverage(command(), 0.8));

        assertFalse(gate.passed());
        assertEquals(75.0, gate.metric("linePercent").doubleValue());
        assertTrue(gate.reason().contains("低于阈值"), gate.reason());
    }

    @Test
    void coverageGateFailsWhenReportMissing() {
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.coverage(command(), 0.8));

        assertFalse(gate.passed());
        assertTrue(gate.reason().contains("未找到 JaCoCo 报告"), gate.reason());
    }

    @Test
    void coverageWithoutThresholdOnlyReports() throws IOException {
        writeCoverageReport(80, 20);
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.of(GateType.COVERAGE, command()));

        assertTrue(gate.passed());
        assertEquals("行覆盖率 80.0%", gate.reason());
    }

    @Test
    void coverageGateAlsoExtractsTestMetrics() throws IOException {
        writeCoverageReport(80, 20);
        executor.result = result(0, "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.coverage(command(), 0.8));

        assertTrue(gate.passed(), gate.reason());
        assertEquals(12, gate.metric("testsRun").intValue());
        assertEquals(80.0, gate.metric("linePercent").doubleValue());
    }

    @Test
    void coverageWithoutBranchCounterOmitsBranchPercent() throws IOException {
        writeCoverageReport(80, 20);
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.coverage(command(), 0.8));

        assertNull(gate.metric("branchPercent"), "无分支数据时不应输出 0.0 误导");
    }

    @Test
    void mutationGatePassesAtThreshold() throws IOException {
        writePitReport(8, 1, 1);
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.mutation(command(), 0.8));

        assertTrue(gate.passed(), gate.reason());
        assertEquals(80.0, gate.metric("mutationPercent").doubleValue());
        assertEquals(8, gate.metric("killed").intValue());
        assertEquals(1, gate.metric("survived").intValue());
        assertEquals(1, gate.metric("noCoverage").intValue());
        assertTrue(gate.reason().contains("≥ 阈值"), gate.reason());
    }

    @Test
    void mutationGateFailsBelowThreshold() throws IOException {
        writePitReport(2, 5, 3);
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.mutation(command(), 0.5));

        assertFalse(gate.passed());
        assertEquals(20.0, gate.metric("mutationPercent").doubleValue());
        assertTrue(gate.reason().contains("低于阈值"), gate.reason());
        assertTrue(gate.reason().contains("NO_COVERAGE=3"), gate.reason());
    }

    @Test
    void mutationGateFailsWhenReportMissing() {
        executor.result = result(0, "BUILD SUCCESS");
        GateRunner runner = new GateRunner(executor, null);

        GateResult gate = runner.run(GateSpec.mutation(command(), 0.5));

        assertFalse(gate.passed());
        assertTrue(gate.reason().contains("未找到 PIT 报告"), gate.reason());
    }
}
