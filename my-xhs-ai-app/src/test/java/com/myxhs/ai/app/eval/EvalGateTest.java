package com.myxhs.ai.app.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvalGate 纯逻辑测试：阈值判定（无模型/无库）。
 */
class EvalGateTest {

    private static Map<String, Object> report(double hallucination, double pass, double completion) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hallucinationRate", hallucination);
        summary.put("passRate", pass);
        summary.put("completionRate", completion);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        return report;
    }

    @Test
    void 全部达标_放行() {
        EvalGate.GateResult r = EvalGate.evaluate(report(0.0, 100.0, 80.0), EvalGate.Thresholds.defaults());
        assertTrue(r.passed());
        assertEquals(0, r.violations().size());
    }

    @Test
    void 幻觉率超限_拦截() {
        EvalGate.GateResult r = EvalGate.evaluate(report(30.0, 100.0, 80.0), EvalGate.Thresholds.defaults());
        assertFalse(r.passed());
        assertTrue(r.violations().get(0).contains("幻觉率"), r.violations().toString());
    }

    @Test
    void 通过率不足_拦截() {
        EvalGate.GateResult r = EvalGate.evaluate(report(0.0, 40.0, 80.0), EvalGate.Thresholds.defaults());
        assertFalse(r.passed());
        assertTrue(r.violations().get(0).contains("通过率"), r.violations().toString());
    }

    @Test
    void 完成率不足_拦截() {
        EvalGate.GateResult r = EvalGate.evaluate(report(0.0, 100.0, 20.0), EvalGate.Thresholds.defaults());
        assertFalse(r.passed());
        assertTrue(r.violations().get(0).contains("完成率"), r.violations().toString());
    }

    @Test
    void 多项违规_全部列出() {
        EvalGate.GateResult r = EvalGate.evaluate(report(20.0, 30.0, 10.0), EvalGate.Thresholds.defaults());
        assertFalse(r.passed());
        assertEquals(3, r.violations().size());
    }

    @Test
    void 自定义阈值生效() {
        EvalGate.GateResult r = EvalGate.evaluate(report(5.0, 70.0, 60.0),
                new EvalGate.Thresholds(1, 80, 70));
        assertFalse(r.passed());
        assertEquals(3, r.violations().size());
    }
}
