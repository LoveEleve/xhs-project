package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真库 smoke 评测（M6）：真实模型+真实数据跑评测集子集，报告落 target/eval-report.json。
 * 需 TEAMO_API_KEY（CI 无凭据自动跳过）；报告不设硬阈值（首次校准），人工/门禁参考。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalsmoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=mcp",
        "spring.ai-datasource.url=jdbc:h2:mem:evalsmokeai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa"
})
@EnabledIfEnvironmentVariable(named = "TEAMO_API_KEY", matches = ".+")
class EvalSmokeRunTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void smoke子集_真库评测_报告落盘() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases.yaml");
        // 子集（验证链路）：A1 归因 + 拒答 各 1 条；完整 18 条 nightly 跑
        List<EvalCase> subset = List.of(
                cases.stream().filter(c -> c.id().startsWith("a1_")).findFirst().orElseThrow(),
                cases.stream().filter(c -> c.id().equals("honesty_no_fabrication")).findFirst().orElseThrow());

        Map<String, Object> report = new EvalRunner(agentHarness).run(subset);
        File out = new File("target/eval-report.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(),
                new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("[eval-smoke] 报告: " + out.getAbsolutePath());

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertTrue(((Number) summary.get("total")).intValue() == 2, "应跑 2 条");
        System.out.println("[eval-smoke] 完成率=" + summary.get("completionRate")
                + "% 幻觉嫌疑率=" + summary.get("hallucinationRate")
                + "% 通过率=" + summary.get("passRate") + "%");
    }
}
