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
 * nightly regression 全量（80 条）：真库+真模型跑完整 regression 集，并落盘报告。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalregfull;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=mcp",
        "spring.ai-datasource.url=jdbc:h2:mem:evalregfullai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:test-key}"
})
@Tag("eval-gate")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class EvalRegressionFullRunTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void regression全量_80条_报告落盘() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases-regression.yaml");
        Map<String, Object> report = new EvalRunner(agentHarness).run(cases);
        File out = new File("docs/reports/nightly-regression-full-report.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(),
                new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(80, ((Number) summary.get("total")).intValue(), "regression 全量应为 80 条");
        System.out.println("[nightly-reg-full] 报告=" + out.getAbsolutePath());
        System.out.println("[nightly-reg-full] pass=" + summary.get("passRate")
                + "% completion=" + summary.get("completionRate")
                + "% hallucination=" + summary.get("hallucinationRate") + "%");
    }
}
