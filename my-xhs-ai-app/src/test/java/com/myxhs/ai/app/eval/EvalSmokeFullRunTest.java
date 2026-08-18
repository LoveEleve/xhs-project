package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * nightly smoke 全量（20 条）：真库+真模型跑完整 smoke 集，并落盘报告。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalsmokefull;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=mcp",
        "spring.ai-datasource.url=jdbc:h2:mem:evalsmokefullai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:test-key}"
})
@Tag("eval-gate")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class EvalSmokeFullRunTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void smoke全量_20条_报告落盘() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases.yaml");
        Map<String, Object> report = new EvalRunner(agentHarness).run(cases);
        File out = new File("docs/reports/nightly-smoke-full-report.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(),
                new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(20, ((Number) summary.get("total")).intValue(), "smoke 全量应为 20 条");
        System.out.println("[nightly-smoke-full] 报告=" + out.getAbsolutePath());
        System.out.println("[nightly-smoke-full] pass=" + summary.get("passRate")
                + "% completion=" + summary.get("completionRate")
                + "% hallucination=" + summary.get("hallucinationRate") + "%");
    }
}
