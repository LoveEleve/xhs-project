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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 E2E 评测（主线路径：MCP）。
 * 目标：验证生产主线（ai-app -> ai-mcp -> 外部服务）而不是 direct 降级路径。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://21.130.247.89:3306/my_xhs_order_0?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=myxhs_ai_ro",
        "spring.datasource.password=${MYXHS_DB_PASSWORD:}",
        "spring.ai-datasource.url=jdbc:mysql://21.130.247.89:3306/my_xhs_ai?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
        "spring.ai-datasource.username=myxhs_ai_rw",
        "spring.ai-datasource.password=${MYXHS_AI_DB_PASSWORD:}",
        "myxhs.ai.tools.mode=mcp",
        "myxhs.ai.mcp.url=http://127.0.0.1:19021/mcp",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:}",
        "myxhs.ai.rag.embedding-url=${ARK_PLAN_BASE_URL:}",
        "myxhs.ai.rag.embedding-key=${ARK_PLAN_API_KEY:}"
})
@Tag("e2e-mcp")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class E2EEvalRunnerMcpTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void e2e_主线路径_mcp_评测报告() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases-e2e.yaml");
        assertFalse(cases.isEmpty(), "E2E 评测集不应为空");

        Map<String, Object> report = new EvalRunner(agentHarness).run(cases);
        File out = new File("target/e2e-eval-report-mcp.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(), new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        double completionRate = ((Number) summary.get("completionRate")).doubleValue();
        assertTrue(completionRate >= 60, "MCP 主线路径 E2E 完成率应 >= 60%");
    }
}
