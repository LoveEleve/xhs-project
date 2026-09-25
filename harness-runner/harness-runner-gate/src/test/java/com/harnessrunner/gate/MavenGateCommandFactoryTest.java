package com.harnessrunner.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenGateCommandFactoryTest {

    @TempDir
    Path projectDir;

    @Test
    void testCommandUsesListFormWithoutShell() {
        GateCommand command = new MavenGateCommandFactory().test(projectDir);
        assertEquals(List.of("mvn", "-B", "-ntp", "test"), command.argv());
        assertEquals(projectDir, command.workingDir());
        assertTrue(command.timeout().toMinutes() >= 5);
    }

    @Test
    void compileCommandRunsOnlyCompileGoal() {
        GateCommand command = new MavenGateCommandFactory().compile(projectDir);
        assertEquals(List.of("mvn", "-B", "-ntp", "compile"), command.argv());
    }

    @Test
    void coverageRunsAgentBeforeTestAndReportAfter() {
        List<String> argv = new MavenGateCommandFactory().coverage(projectDir).argv();
        int prepareAgent = argv.indexOf(MavenGateCommandFactory.JACOCO_PLUGIN + ":prepare-agent");
        int test = argv.indexOf("test");
        int report = argv.indexOf(MavenGateCommandFactory.JACOCO_PLUGIN + ":report");

        assertTrue(prepareAgent >= 0);
        assertTrue(prepareAgent < test);
        assertTrue(test < report);
    }

    @Test
    void mutationCompilesTestsBeforePitAndRequestsXml() {
        List<String> argv = new MavenGateCommandFactory().mutation(projectDir).argv();

        assertTrue(argv.contains("test-compile"));
        assertTrue(argv.contains(MavenGateCommandFactory.PITEST_PLUGIN + ":mutationCoverage"));
        assertTrue(argv.indexOf("test-compile")
                < argv.indexOf(MavenGateCommandFactory.PITEST_PLUGIN + ":mutationCoverage"));
        assertTrue(argv.contains("-DoutputFormats=XML"));
        assertTrue(argv.contains("-DtimestampedReports=false"));
    }

    @Test
    void envIsCarriedIntoCommand() {
        MavenGateCommandFactory factory = new MavenGateCommandFactory("mvn",
                Map.of("JAVA_HOME", "/env/javaEnv/jdk17"));
        GateCommand command = factory.test(projectDir);
        assertEquals("/env/javaEnv/jdk17", command.env().get("JAVA_HOME"));
    }
}
