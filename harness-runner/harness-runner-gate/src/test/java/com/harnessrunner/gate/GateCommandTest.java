package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateCommandTest {

    @Test
    void rejectsEmptyArgv() {
        assertThrows(IllegalArgumentException.class,
                () -> GateCommand.of(List.of(), java.nio.file.Path.of("."), Duration.ofSeconds(1)));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> GateCommand.of(List.of("mvn"), java.nio.file.Path.of("."), Duration.ZERO));
    }

    @Test
    void displayQuotesTokensWithSpaces() {
        GateCommand command = GateCommand.of(
                List.of("sh", "-c", "echo hello world"), java.nio.file.Path.of("."), Duration.ofSeconds(1));
        assertEquals("sh -c \"echo hello world\"", command.display());
    }

    @Test
    void withEnvReturnsNewImmutableCommand() {
        GateCommand base = GateCommand.of(List.of("mvn"), java.nio.file.Path.of("."), Duration.ofSeconds(1));
        GateCommand enriched = base.withEnv("JAVA_HOME", "/env/javaEnv/jdk17");

        assertTrue(base.env().isEmpty());
        assertEquals(Map.of("JAVA_HOME", "/env/javaEnv/jdk17"), enriched.env());
        assertEquals(base.argv(), enriched.argv());
        assertEquals(base.workingDir(), enriched.workingDir());
    }
}
