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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 E2E 评测（依赖外部环境）。
 * 与 EvalSmokeRunTest 的区别：不 mock 任何外部服务，连接真实中间件机。
 *
 * 运行条件：
 *  - MYXHS_LLM_API_KEY 环境变量已设置
 *  - 中间件机 21.130.247.89 可达（MySQL/RocketMQ Dashboard）
 *  - my-xhs-ai-app:19020 可选（SpringBootTest 自带嵌入式 Tomcat）
 *
 * 运行方式：
 *  MYXHS_LLM_API_KEY=xxx mvn test -pl my-xhs-ai-app -Dtest=E2EEvalRunnerTest -DfailIfNoTests=false
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
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:}",
        "myxhs.ai.rag.embedding-url=${ARK_PLAN_BASE_URL:}",
        "myxhs.ai.rag.embedding-key=${ARK_PLAN_API_KEY:}"
})
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class E2EEvalRunnerTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void e2e_真实外部服务_评测报告() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases-e2e.yaml");
        assertFalse(cases.isEmpty(), "E2E 评测集不应为空");
        System.out.println("[e2e] 加载 " + cases.size() + " 个 E2E 评测 case");

        Map<String, Object> report = new EvalRunner(agentHarness).run(cases);

        // 落盘报告
        File out = new File("target/e2e-eval-report.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(),
                new EvalRunner(agentHarness).toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("[e2e] 报告: " + out.getAbsolutePath());

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        int total = ((Number) summary.get("total")).intValue();
        double passRate = ((Number) summary.get("passRate")).doubleValue();
        double completionRate = ((Number) summary.get("completionRate")).doubleValue();
        double hallucinationRate = ((Number) summary.get("hallucinationRate")).doubleValue();

        System.out.println("[e2e] 总计=" + total
                + " 通过率=" + passRate + "%"
                + " 完成率=" + completionRate + "%"
                + " 幻觉率=" + hallucinationRate + "%");

        // E2E 门禁：完成率 >= 60%（首次校准，不设硬阈值）
        assertTrue(completionRate >= 60,
                "E2E 完成率 " + completionRate + "% < 60%（至少 3/5 case 应完成）");
    }
}
