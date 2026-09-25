package com.harnessrunner.gate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MavenGateCommandFactory {

    public static final Duration COMPILE_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration COVERAGE_TIMEOUT = Duration.ofMinutes(15);
    public static final Duration MUTATION_TIMEOUT = Duration.ofMinutes(15);
    public static final String JACOCO_PLUGIN = "org.jacoco:jacoco-maven-plugin:0.8.12";
    public static final String PITEST_PLUGIN = "org.pitest:pitest-maven:1.15.8";

    private final String mvnExecutable;
    private final Map<String, String> env;

    public MavenGateCommandFactory() {
        this("mvn", Map.of());
    }

    public MavenGateCommandFactory(String mvnExecutable, Map<String, String> env) {
        this.mvnExecutable = Objects.requireNonNull(mvnExecutable, "mvnExecutable");
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    public GateCommand compile(Path projectDir) {
        return command(projectDir, COMPILE_TIMEOUT, "compile");
    }

    public GateCommand test(Path projectDir) {
        return command(projectDir, TEST_TIMEOUT, "test");
    }

    public GateCommand coverage(Path projectDir) {
        return command(projectDir, COVERAGE_TIMEOUT,
                JACOCO_PLUGIN + ":prepare-agent", "test", JACOCO_PLUGIN + ":report");
    }

    public GateCommand mutation(Path projectDir) {
        return command(projectDir, MUTATION_TIMEOUT,
                "test-compile", PITEST_PLUGIN + ":mutationCoverage",
                "-DoutputFormats=XML", "-DtimestampedReports=false");
    }

    private GateCommand command(Path projectDir, Duration timeout, String... goals) {
        List<String> argv = new ArrayList<>();
        argv.add(mvnExecutable);
        argv.add("-B");
        argv.add("-ntp");
        argv.addAll(List.of(goals));
        return new GateCommand(argv, projectDir, timeout, env);
    }
}
