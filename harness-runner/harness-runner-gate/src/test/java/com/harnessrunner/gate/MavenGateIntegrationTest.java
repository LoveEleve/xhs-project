package com.harnessrunner.gate;

import com.harnessrunner.domain.gate.GateResult;
import com.harnessrunner.domain.gate.GateType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MavenGateIntegrationTest {

    @Test
    void runsRealMavenTestGate() {
        String projectDir = System.getenv("HARNESS_GATE_IT_DIR");
        assumeTrue(projectDir != null && !projectDir.isBlank(),
                "未设置 HARNESS_GATE_IT_DIR，跳过真实 Maven 集成测试");

        Path target = Path.of(projectDir);
        GateCommand command = new MavenGateCommandFactory().test(target);
        GateRunner runner = new GateRunner(new ProcessExecutor(),
                target.resolve("target").resolve("harness-gate"));

        GateResult result = runner.run(GateSpec.of(GateType.TEST, command));

        System.out.println("[IT] " + result.type().label()
                + " passed=" + result.passed()
                + " exitCode=" + result.exitCode()
                + " durationMs=" + result.durationMs()
                + " metrics=" + result.metrics()
                + " truncated=" + result.outputTruncated()
                + " log=" + result.outputRef());

        assertTrue(result.passed(), result.reason() + "\n" + result.outputExcerpt());
        assertTrue(result.metric("testsRun").intValue() > 0, "未解析到测试数");
    }
}
