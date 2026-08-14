package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 评测门禁（M6-4）：真库+真模型跑关键用例集，红线阈值不过不放行。
 * 触发：mvn test -Peval-gate（需 MYXHS_LLM_API_KEY；CI 无凭据自动跳过）。
 * 用例集：6 场景锚点 + 拒答（发散模型 15 步预算内，全量 18 条走 nightly）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalgate;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=mcp",
        "spring.ai-datasource.url=jdbc:h2:mem:evalgateai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:test-key}"
})
@Tag("eval-gate")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class EvalGateRunTest {

    @Autowired
    private AgentHarness agentHarness;

    @Value("${myxhs.ai.eval.gate.max-hallucination-rate:0.10}")
    private double maxHallucinationRate;

    @Value("${myxhs.ai.eval.gate.min-pass-rate:0.60}")
    private double minPassRate;

    @Value("${myxhs.ai.eval.gate.min-completion-rate:0.40}")
    private double minCompletionRate;

    @Test
    void 关键用例集_门禁检查() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases.yaml");
        // 关键锚点集：6 场景 + 拒答（每场景取 1 条）
        List<EvalCase> subset = List.of(
                pick(cases, "a1_"), pick(cases, "a2_"), pick(cases, "a3_"),
                pick(cases, "b2_mq"), pick(cases, "b3_http"), pick(cases, "b4_replica"),
                cases.stream().filter(c -> c.id().equals("honesty_no_fabrication")).findFirst().orElseThrow());

        Map<String, Object> report = new EvalRunner(agentHarness).run(subset);
        EvalGate.GateResult gate = EvalGate.evaluate(report,
                new EvalGate.Thresholds(maxHallucinationRate, minPassRate, minCompletionRate));

        File out = new File("target/eval-gate-report.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(),
                new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("[eval-gate] 报告: " + out.getAbsolutePath());

        System.out.println("[eval-gate] summary=" + gate.summary());
        assertTrue(gate.passed(), "评测门禁未过: " + gate.violations());
    }

    private static EvalCase pick(List<EvalCase> cases, String prefix) {
        return cases.stream().filter(c -> c.id().startsWith(prefix)).findFirst().orElseThrow();
    }
}
