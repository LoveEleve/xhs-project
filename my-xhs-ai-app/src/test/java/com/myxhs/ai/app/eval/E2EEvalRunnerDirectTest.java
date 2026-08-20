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
 * 真实 E2E 评测（降级路径：direct）。
 * 目标：验证不经过 MCP 时，AI App 自身的 direct tool / Langfuse / Memory / trace 路径。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://21.130.247.89:3306/my_xhs_order_0?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=myxhs_ai_ro",
        "spring.datasource.password=${MYXHS_DB_PASSWORD:}",
        "spring.ai-datasource.url=jdbc:mysql://21.130.247.89:3306/my_xhs_ai?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
        "spring.ai-datasource.username=myxhs_ai_rw",
        "spring.ai-datasource.password=${MYXHS_AI_DB_PASSWORD:}",
        "myxhs.ai.tools.mode=direct",
        "myxhs.ai.log-search.files=my-xhs-order=/data/workspace/my-xhs/logs/my-xhs-order.log,my-xhs-inventory=/data/workspace/my-xhs/logs/my-xhs-inventory.log,my-xhs-gateway=/data/workspace/my-xhs/logs/my-xhs-gateway.log",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:}",
        "myxhs.ai.rag.embedding-url=${ARK_PLAN_BASE_URL:}",
        "myxhs.ai.rag.embedding-key=${ARK_PLAN_API_KEY:}"
})
@Tag("e2e-direct")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class E2EEvalRunnerDirectTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void e2e_降级路径_direct_评测报告() throws Exception {
        List<EvalCase> all = new EvalCaseLoader().load("eval/cases-e2e.yaml");
        List<EvalCase> subset = all.stream()
                .filter(c -> !"e2e_dlq_query".equals(c.id())) // direct 路径不需要 MCP 主线能力
                .toList();
        assertFalse(subset.isEmpty(), "direct 子集不应为空");

        Map<String, Object> report = new EvalRunner(agentHarness).run(subset);
        File out = new File("target/e2e-eval-report-direct.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(), new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        double completionRate = ((Number) summary.get("completionRate")).doubleValue();
        assertTrue(completionRate >= 60, "direct 降级路径 E2E 完成率应 >= 60%");
    }
}
