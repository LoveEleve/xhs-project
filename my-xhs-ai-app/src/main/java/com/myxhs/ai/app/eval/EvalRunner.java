package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentHarness;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.AgentStep;
import com.myxhs.ai.app.service.agent.harness.RunStatus;
import com.myxhs.ai.app.service.agent.harness.TerminationReason;
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
    /** M14：LLM-as-judge（可选；enabled=false 时 score=-1 不进汇总） */
    private EvalJudge judge = new EvalJudge(null, false);
    /** M14：bad case 回流收集器（可选；null=不收集） */
    private BadCaseCollector badCaseCollector;

    public EvalRunner(AgentHarness harness) {
        this.harness = harness;
    }

    public EvalRunner withJudge(EvalJudge judge) {
        this.judge = judge;
        return this;
    }

    public EvalRunner withBadCaseCollector(BadCaseCollector collector) {
        this.badCaseCollector = collector;
        return this;
    }

    /** 执行评测集；返回报告对象（可序列化为 JSON）。M13：profileFor 非空时按画像跑（双 Agent 对比评测） */
    public Map<String, Object> run(List<EvalCase> cases) {
        return run(cases, null);
    }

    /** M13：per-case 画像分派（FULL=单 Agent 基线；dispatcher 分派=双 Agent）。返回 null 的 case 用默认单 Agent */
    public Map<String, Object> run(List<EvalCase> cases,
                                   java.util.function.Function<EvalCase, com.myxhs.ai.app.service.agent.profile.AgentProfile> profileFor) {
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
                com.myxhs.ai.app.service.agent.profile.AgentProfile p =
                        profileFor == null ? null : profileFor.apply(c);
                AgentRun run = p == null
                        ? harness.run(c.query())
                        : harness.run(c.query(), p);
                // 限流类外部故障（模型不可用/超时）重试一次：评测门禁不应被随机限流打红
                if (RunStatus.FAILED.name().equals(run.status().name())
                        && TerminationReason.MODEL_UNAVAILABLE == run.terminationReason()) {
                    log.warn("[eval] case={} 模型不可用，重试一次", c.id());
                    run = harness.run(c.query());
                }
                r.put("status", run.status().name());
                r.put("terminationReason", run.terminationReason() == null ? null
                        : run.terminationReason().name());
                r.put("evidenceCount", run.evidenceChain().size());
                r.put("steps", run.steps().size());
                r.put("finalAnswer", run.finalAnswer());
                r.put("durationMs", System.currentTimeMillis() - t0);
                r.put("tokensTotal", run.steps().stream().mapToInt(AgentStep::tokensUsed).sum());
                r.put("toolUsage", ToolUsageAnalyzer.analyze(run));

                List<String> hardFails = asserter.checkHard(c, run);
                r.put("hardFails", hardFails);
                // M14 LLM-as-judge：主观质量分（0-5；未启用=-1）
                if (judge != null && judge.enabled()) {
                    double js = judge.score(c.query(), run.finalAnswer());
                    r.put("judgeScore", js);
                }
                boolean pass = hardFails.isEmpty();
                // 幻觉检测仅针对模型结论（SUCCEEDED）；PARTIAL 为确定性摘要（步骤数/预算等流程数字会误报）
                if (c.numbersConsistent()
                        && RunStatus.SUCCEEDED.name().equals(run.status().name())
                        && run.evidenceChain().size() > 0) {
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

        // M14 bad case 回流：质量失败（非 FAILED）追加到回流文件
        if (badCaseCollector != null) {
            List<Map<String, Object>> bad = results.stream()
                    .filter(badCaseCollector::isQualityFailure).toList();
            badCaseCollector.append(bad, 0);
        }

        int n = Math.max(1, cases.size());
        long totalTokens = 0;
        long totalDuration = 0;
        int divergent = 0;
        for (Map<String, Object> r : results) {
            totalTokens += ((Number) r.getOrDefault("tokensTotal", 0)).longValue();
            totalDuration += ((Number) r.getOrDefault("durationMs", 0)).longValue();
            Object usage = r.get("toolUsage");
            if (usage instanceof Map<?, ?> u && Boolean.TRUE.equals(u.get("divergent"))) {
                divergent++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", cases.size());
        summary.put("passed", passed);
        summary.put("passRate", round(passed * 100.0 / n));
        summary.put("completionRate", round(completed * 100.0 / n));
        summary.put("hallucinationSuspected", hallucinationSuspected);
        summary.put("hallucinationRate", round(hallucinationSuspected * 100.0 / n));
        summary.put("divergenceSuspected", divergent);
        summary.put("divergenceRate", round(divergent * 100.0 / n));
        summary.put("avgSteps", round(totalSteps * 1.0 / n));
        summary.put("avgEvidence", round(totalEvidence * 1.0 / n));
        summary.put("avgTokensPerRun", round(totalTokens * 1.0 / n));
        // M14 judge 汇总（未启用=无字段）
        if (judge != null && judge.enabled()) {
            double totalJudge = results.stream()
                    .mapToDouble(x -> ((Number) x.getOrDefault("judgeScore", -1.0)).doubleValue())
                    .filter(v -> v >= 0).sum();
            long scored = results.stream()
                    .mapToDouble(x -> ((Number) x.getOrDefault("judgeScore", -1.0)).doubleValue())
                    .filter(v -> v >= 0).count();
            summary.put("avgJudgeScore", scored == 0 ? -1 : round(totalJudge / scored));
        }
        summary.put("avgDurationMs", round(totalDuration * 1.0 / n));

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
