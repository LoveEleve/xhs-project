package com.harnessrunner.app.benchmark;

import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.domain.gate.GateType;
import com.harnessrunner.gate.GateRunner;
import com.harnessrunner.gate.GateSpec;
import com.harnessrunner.gate.MavenGateCommandFactory;
import com.harnessrunner.gate.ProcessExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GateEffectivenessBenchmarkTest {

    private static final double COVERAGE_THRESHOLD = 0.8;

    private enum Kind {
        BASELINE, FAULT, INFO
    }

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId>
                <artifactId>bench-demo</artifactId>
                <version>1.0</version>
                <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.2</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>3.2.5</version>
                        </plugin>
                        <plugin>
                            <groupId>org.pitest</groupId>
                            <artifactId>pitest-maven</artifactId>
                            <version>1.15.8</version>
                            <dependencies>
                                <dependency>
                                    <groupId>org.pitest</groupId>
                                    <artifactId>pitest-junit5-plugin</artifactId>
                                    <version>1.2.1</version>
                                </dependency>
                            </dependencies>
                            <configuration>
                                <targetClasses><param>demo.*</param></targetClasses>
                                <targetTests><param>demo.*</param></targetTests>
                                <outputFormats><param>XML</param></outputFormats>
                                <timestampedReports>false</timestampedReports>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    private static final String CALC = """
            package demo;

            public class Calc {
                public int add(int a, int b) {
                    return a + b;
                }

                public int sub(int a, int b) {
                    return a - b;
                }
            }
            """;

    private static final String CALC_TEST = """
            package demo;

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            class CalcTest {
                @Test
                void adds() {
                    assertEquals(3, new Calc().add(1, 2));
                }

                @Test
                void subs() {
                    assertEquals(1, new Calc().sub(3, 2));
                }
            }
            """;

    private static final String CALC_MUL = CALC.substring(0, CALC.lastIndexOf('}'))
            + "    public int mul(int a, int b) {\n        return a * b;\n    }\n}\n";

    private static final String CALC_TEST_MUL = CALC_TEST.substring(0, CALC_TEST.lastIndexOf('}'))
            + "    @Test\n    void muls() {\n        assertEquals(6, new Calc().mul(2, 3));\n    }\n}\n";

    @TempDir
    Path tempDir;

    @Test
    void runsFaultInjectionBenchmark() throws Exception {
        assumeTrue("1".equals(System.getenv("HARNESS_BENCH")),
                "设置 HARNESS_BENCH=1 运行门禁有效性基准（会真实执行多次 mvn）");

        Path project = tempDir.resolve("bench-demo");
        writeProject(project);
        initGitRepository(project);

        Path evidenceDir = Path.of(System.getenv().getOrDefault("HARNESS_BENCH_DIR",
                "../evidence/benchmark"));
        Files.createDirectories(evidenceDir);
        MavenGateCommandFactory maven = new MavenGateCommandFactory();
        GateRunner runner = new GateRunner(new ProcessExecutor(), evidenceDir.resolve("logs"));

        List<Row> rows = new ArrayList<>();
        rows.addAll(baseline(runner, maven, project));

        Path calc = project.resolve("src/main/java/demo/Calc.java");
        Path calcTest = project.resolve("src/test/java/demo/CalcTest.java");

        write(calc, CALC + System.lineSeparator() + "this is not java");
        rows.add(row("compile_error", GateType.COMPILE, Kind.FAULT, false,
                runner.run(GateSpec.of(GateType.COMPILE, maven.compile(project)))));
        write(calc, CALC);

        Path faulty = project.resolve("src/test/java/demo/FaultyTest.java");
        write(faulty, """
                package demo;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.fail;

                class FaultyTest {
                    @Test
                    void broken() {
                        fail("注入的失败测试");
                    }
                }
                """);
        rows.add(row("failing_test", GateType.TEST, Kind.FAULT, false,
                runner.run(GateSpec.of(GateType.TEST, maven.test(project)))));
        Files.delete(faulty);

        Path backup = tempDir.resolve("CalcTest.java.bak");
        Files.move(calcTest, backup, StandardCopyOption.REPLACE_EXISTING);
        rows.add(row("no_tests", GateType.TEST, Kind.FAULT, false,
                runner.run(GateSpec.of(GateType.TEST, maven.test(project)))));
        Files.move(backup, calcTest, StandardCopyOption.REPLACE_EXISTING);

        write(calcTest, """
                package demo;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                @Disabled("全部跳过")
                class CalcTest {
                    @Test
                    void adds() {
                        assertEquals(3, new Calc().add(1, 2));
                    }

                    @Test
                    void subs() {
                        assertEquals(1, new Calc().sub(3, 2));
                    }
                }
                """);
        rows.add(row("all_tests_skipped", GateType.TEST, Kind.FAULT, false,
                runner.run(GateSpec.of(GateType.TEST, maven.test(project)))));
        write(calcTest, CALC_TEST);

        write(calcTest, """
                package demo;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                class CalcTest {
                    @Test
                    void adds() {
                        assertTrue(true);
                    }

                    @Test
                    void subs() {
                        assertTrue(true);
                    }
                }
                """);
        rows.add(row("fake_test", GateType.TEST, Kind.INFO, true,
                runner.run(GateSpec.of(GateType.TEST, maven.test(project)))));
        rows.add(row("fake_test_mutation", GateType.MUTATION, Kind.FAULT, false,
                runner.run(GateSpec.mutation(maven.mutation(project), COVERAGE_THRESHOLD))));
        write(calcTest, CALC_TEST);

        StringBuilder uncovered = new StringBuilder(CALC.substring(0, CALC.lastIndexOf('}')));
        for (int i = 1; i <= 10; i++) {
            uncovered.append("    public int unused").append(i)
                    .append("() { return ").append(i).append("; }\n");
        }
        uncovered.append("}\n");
        write(calc, uncovered.toString());
        rows.add(row("coverage_drop", GateType.COVERAGE, Kind.FAULT, false,
                runner.run(GateSpec.coverage(maven.coverage(project), COVERAGE_THRESHOLD))));
        write(calc, CALC);

        write(calc, CALC_MUL);
        write(calcTest, CALC_TEST_MUL);
        rows.add(row("diff_coverage_baseline", GateType.DIFF_COVERAGE, Kind.BASELINE, true,
                runner.run(GateSpec.diffCoverage(maven.coverage(project), "HEAD", COVERAGE_THRESHOLD))));
        write(calc, CALC);
        write(calcTest, CALC_TEST);

        write(calc, uncovered.toString());
        rows.add(row("diff_coverage_drop", GateType.DIFF_COVERAGE, Kind.FAULT, false,
                runner.run(GateSpec.diffCoverage(maven.coverage(project), "HEAD", COVERAGE_THRESHOLD))));
        write(calc, CALC);

        String report = render(rows, evidenceDir);
        Files.writeString(evidenceDir.resolve("gate-effectiveness.md"), report, StandardCharsets.UTF_8);
        System.out.println("[BENCHMARK] " + summary(rows));

        for (Row row : rows) {
            if (row.kind() == Kind.INFO) {
                continue;
            }
            assertEquals(row.expectedPassed(), row.actualPassed(),
                    row.name() + " 与期望不符: " + row.reason());
        }
    }

    private List<Row> baseline(GateRunner runner, MavenGateCommandFactory maven, Path project) {
        List<Row> rows = new ArrayList<>();
        rows.add(row("baseline_compile", GateType.COMPILE, Kind.BASELINE, true,
                runner.run(GateSpec.of(GateType.COMPILE, maven.compile(project)))));
        rows.add(row("baseline_test", GateType.TEST, Kind.BASELINE, true,
                runner.run(GateSpec.of(GateType.TEST, maven.test(project)))));
        rows.add(row("baseline_coverage", GateType.COVERAGE, Kind.BASELINE, true,
                runner.run(GateSpec.coverage(maven.coverage(project), COVERAGE_THRESHOLD))));
        rows.add(row("baseline_mutation", GateType.MUTATION, Kind.BASELINE, true,
                runner.run(GateSpec.mutation(maven.mutation(project), COVERAGE_THRESHOLD))));
        return rows;
    }

    private static Row row(String name, GateType gate, Kind kind, boolean expectedPassed,
                           GateResult result) {
        return new Row(name, gate, kind, expectedPassed, result.passed(), result.reason(),
                result.outputRef() == null ? "-" : result.outputRef());
    }

    private static void writeProject(Path project) throws IOException {
        write(project.resolve("pom.xml"), POM);
        write(project.resolve("src/main/java/demo/Calc.java"), CALC);
        write(project.resolve("src/test/java/demo/CalcTest.java"), CALC_TEST);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void initGitRepository(Path project) throws Exception {
        git(project, "init");
        git(project, "config", "user.email", "bench@example.com");
        git(project, "config", "user.name", "bench");
        git(project, "add", ".");
        git(project, "-c", "commit.gpgsign=false", "commit", "-m", "baseline");
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> command = new ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " 失败: " + output);
        }
    }

    private static String summary(List<Row> rows) {
        long faults = rows.stream().filter(row -> row.kind() == Kind.FAULT).count();
        long detected = rows.stream()
                .filter(row -> row.kind() == Kind.FAULT && !row.actualPassed())
                .count();
        long baselineTotal = rows.stream().filter(row -> row.kind() == Kind.BASELINE).count();
        long baselineGreen = rows.stream()
                .filter(row -> row.kind() == Kind.BASELINE && row.actualPassed())
                .count();
        return "故障用例 " + faults + "，检出 " + detected + "（" + (detected * 100 / faults) + "%），"
                + "基线全绿 " + baselineGreen + "/" + baselineTotal;
    }

    private static String render(List<Row> rows, Path evidenceDir) {
        StringBuilder report = new StringBuilder();
        report.append("# 门禁有效性基准（故障注入）\n\n");
        report.append("> 生成: ").append(java.time.LocalDate.now()).append("\n");
        report.append("> 方法：对最小 Maven 工程注入 8 类故障/对照场景（7 类应拦截 + 1 类对照），"
                + "另跑 5 项基线（含增量覆盖率基线）验证无假阳性；全部真实执行 `mvn`。\n\n");
        report.append("**结论**：").append(summary(rows)).append("\n\n");
        report.append("| 场景 | 门禁 | 期望 | 实际 | 判定 | 原因 | 日志 |\n");
        report.append("|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            report.append("| ").append(row.name())
                    .append(" | ").append(row.gate().name())
                    .append(" | ").append(row.expectedPassed() ? "绿" : "红")
                    .append(" | ").append(row.actualPassed() ? "绿" : "红")
                    .append(" | ").append(verdict(row))
                    .append(" | ").append(row.reason().replace("|", "\\|"))
                    .append(" | ").append(relativeLog(row.logRef(), evidenceDir))
                    .append(" |\n");
        }
        report.append("\n## 边界说明\n\n");
        report.append("- `fake_test` 对照：TEST 门禁只验证\"测试发生\"（汇总 + 有效执行数 > 0），"
                + "不验证断言有效性 → 绿；这是设计边界，不是缺陷。\n");
        report.append("- `fake_test_mutation`：MUTATION 门禁用变异得分 "
                + "`KILLED / (KILLED + SURVIVED + NO_COVERAGE)` 判定，假测试全 NO_COVERAGE → 红——"
                + "假测试漏检由变异门禁闭合。\n");
        report.append("- 变异门禁前提：项目声明 `pitest-maven` + `pitest-junit5-plugin`；"
                + "覆盖率门禁无此前提（CLI 注入插件坐标）。\n");
        return report.toString();
    }

    private static String verdict(Row row) {
        return switch (row.kind()) {
            case BASELINE -> row.expectedPassed() == row.actualPassed() ? "符合" : "不符";
            case FAULT -> row.actualPassed() ? "漏检" : "检出";
            case INFO -> "对照（边界说明）";
        };
    }

    private static String relativeLog(String logRef, Path evidenceDir) {
        if ("-".equals(logRef)) {
            return "-";
        }
        try {
            return evidenceDir.relativize(Path.of(logRef)).toString();
        } catch (IllegalArgumentException e) {
            return logRef;
        }
    }

    private record Row(String name, GateType gate, Kind kind, boolean expectedPassed,
                       boolean actualPassed, String reason, String logRef) {
    }
}
