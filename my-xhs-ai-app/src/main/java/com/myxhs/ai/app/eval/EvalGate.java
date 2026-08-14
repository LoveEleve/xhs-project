package com.myxhs.ai.app.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测门禁（M6-4）：对 EvalRunner 报告做红线阈值检查——不过不放行（PR 门禁）。
 * 指标来源：EvalRunner summary（完成率/幻觉率/通过率）。
 * 阈值配置：myxhs.ai.eval.gate.*（实测校准后收紧）。
 */
public class EvalGate {

    public record Thresholds(double maxHallucinationRate, double minPassRate, double minCompletionRate) {
        /** 阈值语义：百分比（10=10%，60=60%）——与 EvalRunner summary 数值一致 */
        public static Thresholds defaults() {
            // 保守默认：先放行校准，评测数据累积后收紧（M6 校准项）
            return new Thresholds(10, 60, 40);
        }
    }

    public record GateResult(boolean passed, List<String> violations, Map<String, Object> summary) {
    }

    /** 对报告执行门禁检查；返回通过与否 + 违规项 */
    public static GateResult evaluate(Map<String, Object> report, Thresholds t) {
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        List<String> violations = new ArrayList<>();
        double hallucination = num(summary, "hallucinationRate");
        double pass = num(summary, "passRate");
        double completion = num(summary, "completionRate");
        if (hallucination > t.maxHallucinationRate()) {
            violations.add("幻觉率 " + hallucination + "% > 上限 " + t.maxHallucinationRate() + "%");
        }
        if (pass < t.minPassRate()) {
            violations.add("通过率 " + pass + "% < 要求 " + t.minPassRate() + "%");
        }
        if (completion < t.minCompletionRate()) {
            violations.add("完成率 " + completion + "% < 要求 " + t.minCompletionRate() + "%");
        }
        return new GateResult(violations.isEmpty(), violations, summary);
    }

    private static double num(Map<String, Object> summary, String key) {
        Object v = summary == null ? null : summary.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
