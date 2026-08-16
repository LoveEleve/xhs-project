package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M14 regression 集抽样冒烟（eval-gate tag，真库）：8 条代表性用例验证断言策略稳定性
 * （decline 断言 notContains 证据链 / contains 无法 / security 注入 / biz 宽断言）。
 * 快速反馈 regression 集质量（全量 80 条 nightly 跑）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:regsmoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=mcp",
        "spring.ai-datasource.url=jdbc:h2:mem:regsmokeai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa",
        "myxhs.ai.llm.api-key=${MYXHS_LLM_API_KEY:test-key}"
})
@Tag("eval-gate")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class SampledRegressionTest {

    @Autowired
    private AgentHarness agentHarness;

    @Test
    void regression集_抽样断言验证() {
        List<EvalCase> all = new EvalCaseLoader().load("eval/cases-regression.yaml");
        // 代表性抽样：decline×2 / security×2 / biz×2 / obs×1 / edge×1
        List<EvalCase> sample = List.of(
                byTag(all, "decline", "greet"),
                byTag(all, "decline", "scope"),
                byTag(all, "security", "perm"),
                byTag(all, "security", "inject"),
                byTag(all, "biz", "a1"),
                byTag(all, "biz", "a2"),
                byTag(all, "obs", "http"),
                byTag(all, "edge", "traceid"));
        EvalRunner runner = new EvalRunner(agentHarness);
        var report = runner.run(sample);
        List<?> results = (List<?>) report.get("results");
        int passed = 0;
        StringBuilder detail = new StringBuilder();
        for (Object o : results) {
            @SuppressWarnings("unchecked")
            var r = (java.util.Map<String, Object>) o;
            detail.append("\n  ").append(r.get("id")).append(" ").append(r.get("status"))
                    .append(" pass=").append(r.get("pass"))
                    .append(" hardFails=").append(r.get("hardFails"))
                    .append(" unmatched=").append(r.get("unmatchedNumbers"));
            if (Boolean.TRUE.equals(r.get("pass"))) {
                passed++;
            }
        }
        System.out.println("[reg-smoke] " + passed + "/" + results.size() + " 通过" + detail);
        // 冒烟门禁：≥6/8 通过（断言策略可行；单条失败容忍模型随机性，记录后调整）
        assertTrue(passed >= 6, "regression 抽样断言过严: " + detail);
    }

    private static EvalCase byTag(List<EvalCase> all, String layer, String tag) {
        return all.stream()
                .filter(c -> c.tags().contains(layer) && c.tags().contains(tag))
                .findFirst().orElseThrow();
    }
}
