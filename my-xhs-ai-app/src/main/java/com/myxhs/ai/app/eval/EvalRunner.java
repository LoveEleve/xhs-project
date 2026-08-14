package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测执行器（M6）：逐 case 跑 Harness（真模型+真库），执行断言，产出 JSON 报告与质量指标。
 * 指标：通过率（硬断言）、完成率（SUCCEEDED 比例）、幻觉嫌疑率（数字不一致）、平均证据/步骤。
 */
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final AgentHarness harness;
    private final EvalAsserter asserter = new EvalAsserter();
    private final ObjectMapper om = new ObjectMapper();

    public EvalRunner(AgentHarness harness) {
        this.harness = harness;
    }

    /** 执行评测集；返回报告对象（可序列化为 JSON） */
    public Map<String, Object> run(List<EvalCase> cases) {
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int completed = 0;
        int hallucinationSuspected = 0;
        long totalSteps = 0;
        long totalEvidence = 0;

        int idx = 0;
        for (EvalCase c : cases) {
            idx++;
            long t0 = System.currentTimeMillis();
            log.info("[eval] [{}/{}] case={} 开始 query={}", idx, cases.size(), c.id(), c.query());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.id());
            r.put("query", c.query());
            try {
                AgentRun run = harness.run(c.query());
                r.put("status", run.status().name());
                r.put("terminationReason", run.terminationReason() == null ? null
                        : run.terminationReason().name());
                r.put("evidenceCount", run.evidenceChain().size());
                r.put("steps", run.steps().size());
                r.put("finalAnswer", run.finalAnswer());

                List<String> hardFails = asserter.checkHard(c, run);
                r.put("hardFails", hardFails);
                boolean pass = hardFails.isEmpty();
                // 幻觉检测仅针对模型结论（SUCCEEDED）；PARTIAL 为确定性摘要（步骤数/预算等流程数字会误报）
                if (c.numbersConsistent() && RunStatus.SUCCEEDED.name().equals(run.status().name())) {
                    Set<String> unmatched = asserter.checkNumberConsistency(run);
                    r.put("unmatchedNumbers", new ArrayList<>(unmatched));
                    if (!unmatched.isEmpty()) {
                        hallucinationSuspected++;
                        r.put("hallucinationSuspected", true);
                        pass = false; // 数字不一致 = 幻觉嫌疑，计入失败
                    }
                }
                r.put("pass", pass);
                if (pass) {
                    passed++;
                }
                if (RunStatus.SUCCEEDED.name().equals(run.status().name())) {
                    completed++;
                }
                totalSteps += run.steps().size();
                totalEvidence += run.evidenceChain().size();
            } catch (Exception e) {
                r.put("pass", false);
                r.put("error", e.getMessage());
                log.warn("[eval] case={} 执行异常: {}", c.id(), e.getMessage());
            }
            log.info("[eval] [{}/{}] case={} 完成 status={} pass={} 耗时={}s",
                    idx, cases.size(), c.id(), r.get("status"), r.get("pass"),
                    (System.currentTimeMillis() - t0) / 1000);
            results.add(r);
        }

        int n = Math.max(1, cases.size());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", cases.size());
        summary.put("passed", passed);
        summary.put("passRate", round(passed * 100.0 / n));
        summary.put("completionRate", round(completed * 100.0 / n));
        summary.put("hallucinationSuspected", hallucinationSuspected);
        summary.put("hallucinationRate", round(hallucinationSuspected * 100.0 / n));
        summary.put("avgSteps", round(totalSteps * 1.0 / n));
        summary.put("avgEvidence", round(totalEvidence * 1.0 / n));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite", "smoke");
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("summary", summary);
        report.put("results", results);
        log.info("[eval] 完成: total={} pass={} completion={}% hallucination={}%",
                cases.size(), passed, summary.get("completionRate"), summary.get("hallucinationRate"));
        return report;
    }

    public String toJson(Map<String, Object> report) {
        try {
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            return "{\"error\":\"报告序列化失败\"}";
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
