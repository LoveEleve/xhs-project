package com.harnessrunner.gate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record GateCommand(
        List<String> argv,
        Path workingDir,
        Duration timeout,
        Map<String, String> env) {

    public GateCommand {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv 不能为空");
        }
        argv = List.copyOf(argv);
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正: " + timeout);
        }
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public static GateCommand of(List<String> argv, Path workingDir, Duration timeout) {
        return new GateCommand(argv, workingDir, timeout, Map.of());
    }

    public GateCommand withEnv(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(env);
        merged.put(name, value);
        return new GateCommand(argv, workingDir, timeout, merged);
    }

    public String display() {
        return argv.stream().map(GateCommand::quote).collect(Collectors.joining(" "));
    }

    private static String quote(String token) {
        if (token.isEmpty() || token.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\'')) {
            return '"' + token.replace("\"", "\\\"") + '"';
        }
        return token;
    }
}
