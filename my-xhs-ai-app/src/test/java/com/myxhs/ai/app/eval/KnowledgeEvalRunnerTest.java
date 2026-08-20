package com.myxhs.ai.app.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.run.RunManager;
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

/** 第一版系统知识问答评测。 */
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
@Tag("knowledge-eval")
@EnabledIfEnvironmentVariable(named = "MYXHS_LLM_API_KEY", matches = ".+")
class KnowledgeEvalRunnerTest {

    @Autowired
    private RunManager runManager;

    @Test
    void knowledge_eval_经过真实RunManager路由() throws Exception {
        List<EvalCase> cases = new EvalCaseLoader().load("eval/cases-knowledge.yaml");
        assertFalse(cases.isEmpty());
        int passed = 0;
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (EvalCase c : cases) {
            RunManager.RunEntry entry = runManager.submit(c.query(), "knowledge-eval", "conv-" + c.id());
            AgentRun run = entry.future().join();
            String answer = run == null ? "" : run.finalAnswer();
            boolean ok = run != null && c.statusIn().contains(run.status().name())
                    && containsExpected(c, answer);
            if (ok) passed++;
            results.add(Map.of("id", c.id(), "status", run == null ? "NULL" : run.status().name(),
                    "pass", ok, "answer", answer == null ? "" : answer));
        }
        Map<String, Object> report = Map.of("total", cases.size(), "passed", passed,
                "passRate", passed * 100.0 / cases.size(), "results", results);
        File out = new File("target/knowledge-eval-report.json");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(), new ObjectMapper().writeValueAsBytes(report));
        System.out.println("[knowledge-eval] total=" + cases.size() + " passed=" + passed);
        assertTrue(passed * 100.0 / cases.size() >= 80, "知识问答通过率应 >= 80%");
    }

    private static boolean containsExpected(EvalCase c, String answer) {
        String text = answer == null ? "" : answer;
        boolean contains = c.contains().stream().allMatch(text::contains);
        boolean any = c.anyContains().isEmpty() || c.anyContains().stream().anyMatch(text::contains);
        return contains && any;
    }
}
