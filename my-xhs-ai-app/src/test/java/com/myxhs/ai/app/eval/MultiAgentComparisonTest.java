package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.profile.AgentDispatcher;
import com.myxhs.ai.app.service.agent.profile.AgentProfiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13 多智能体对比评测（eval-gate tag，真库+真模型，~30 分钟）：
 * 同一锚点子集跑 单 Agent（FULL）vs 双 Agent（分派），产出对比报告（通过率/幻觉率/步骤数/token）。
 * 结论决策（D-A）：双 Agent 无退化且成本下降 → 全量；否则克制论证。
 * 触发：mvn test -Peval-gate -Dtest=MultiAgentComparisonTest
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:macomp;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=mcp",
        "spring.ai-datasource.url=jdbc:h2:mem:macompai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:test-key}"
})
@Tag("eval-gate")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class MultiAgentComparisonTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void 单Agent对比双Agent() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases.yaml");
        List<EvalCase> subset = List.of(
                pick(cases, "a1_"), pick(cases, "a2_"), pick(cases, "a3_"),
                pick(cases, "b2_mq"), pick(cases, "b3_http"), pick(cases, "b4_replica"),
                cases.stream().filter(c -> c.id().equals("honesty_no_fabrication")).findFirst().orElseThrow());

        AgentDispatcher dispatcher = new AgentDispatcher();
        EvalRunner runner = new EvalRunner(agentHarness);

        System.out.println("[m13] 开始单 Agent（FULL）对比评测……");
        Map<String, Object> single = runner.run(subset, c -> AgentProfiles.FULL);
        System.out.println("[m13] 开始双 Agent（分派）对比评测……");
        Map<String, Object> multi = runner.run(subset, c -> dispatcher.dispatch(c.query()));

        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("mode", "M13 multi-agent comparison");
        report.put("singleAgent", single.get("summary"));
        report.put("multiAgent", multi.get("summary"));
        report.put("dispatchMatrix", multi.get("results") == null ? List.of() : multi.get("results"));

        File out = new File("target/multi-agent-comparison.json");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), runner.toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("[m13] 对比报告: " + out.getAbsolutePath());

        Map<?, ?> s = (Map<?, ?>) single.get("summary");
        Map<?, ?> m = (Map<?, ?>) multi.get("summary");
        double singlePass = ((Number) s.get("passRate")).doubleValue();
        double multiPass = ((Number) m.get("passRate")).doubleValue();
        double singleHal = ((Number) s.get("hallucinationRate")).doubleValue();
        double multiHal = ((Number) m.get("hallucinationRate")).doubleValue();
        System.out.printf("[m13] 单 Agent: pass=%.1f%% halluc=%.1f%% | 双 Agent: pass=%.1f%% halluc=%.1f%%%n",
                singlePass, singleHal, multiPass, multiHal);
        // 门禁：双 Agent 无退化（通过率不低于单 Agent -10pt 容差，幻觉率不超 10%）
        assertTrue(multiPass >= singlePass - 10.0,
                "双 Agent 通过率退化: 单=" + singlePass + " 双=" + multiPass);
        assertTrue(multiHal <= 10.0, "双 Agent 幻觉率超标: " + multiHal);
    }

    private static EvalCase pick(List<EvalCase> cases, String prefix) {
        return cases.stream().filter(c -> c.id().startsWith(prefix)).findFirst().orElseThrow();
    }
}
