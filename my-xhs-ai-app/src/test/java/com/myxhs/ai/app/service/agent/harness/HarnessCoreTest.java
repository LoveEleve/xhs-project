package com.myxhs.ai.app.service.agent.harness;

import com.myxhs.ai.tools.AgentToolBinder;
import com.myxhs.ai.tools.ToolParamValidators;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Harness 核心单元测试（纯逻辑，无 DB/模型）。
 * 覆盖：预算三重封顶、循环检测两模式（含空转步与误杀防护）、策略 allowlist/参数校验/L3 HITL 门、
 * 证据链语义去重、policy 拒绝耗尽、取消/状态机。
 */
class HarnessCoreTest {

    private static Map<String, String> window(String w) {
        return Map.of("window", w);
    }

    // ---- LoopCtrl：预算三重封顶 + policy 拒绝耗尽 ----

    @Test
    void 预算步骤数封顶() {
        LoopCtrl ctrl = new LoopCtrl(new AgentBudget(3, 100_000, 100));
        assertNull(ctrl.checkBeforeStep());
        ctrl.recordStep(1);
        ctrl.recordStep(1);
        ctrl.recordStep(1);
        assertEquals(TerminationReason.BUDGET_STEPS, ctrl.checkBeforeStep());
    }

    @Test
    void 预算Token封顶() {
        LoopCtrl ctrl = new LoopCtrl(new AgentBudget(100, 10, 100));
        ctrl.recordStep(6);
        ctrl.recordStep(4);
        assertEquals(TerminationReason.BUDGET_TOKENS, ctrl.checkBeforeStep());
    }

    @Test
    void 预算成本封顶() {
        LoopCtrl ctrl = new LoopCtrl(new AgentBudget(100, 100_000, 1.0));
        ctrl.recordCost(1.2);
        assertEquals(TerminationReason.BUDGET_COST, ctrl.checkBeforeStep());
    }

    @Test
    void 策略拒绝耗尽_终止() {
        LoopCtrl ctrl = new LoopCtrl(new AgentBudget(100, 100_000, 100), 3);
        ctrl.recordPolicyDenied();
        ctrl.recordPolicyDenied();
        assertNull(ctrl.checkBeforeStep());
        ctrl.recordPolicyDenied();
        assertEquals(TerminationReason.POLICY_EXHAUSTED, ctrl.checkBeforeStep());
    }

    @Test
    void 非法预算参数_抛异常() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(0, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(10, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new LoopCtrl(new AgentBudget(10, 100, 1), 0));
    }

    // ---- LoopDetector：两种死循环模式 ----

    @Test
    void 连续同工具同参数_触发循环终止() {
        LoopDetector d = new LoopDetector(3, 6);
        Map<String, String> args = window("2026-08-01~2026-08-07");
        assertNull(d.recordToolCall("queryOrderVolume", args, "h1"));
        assertNull(d.recordToolCall("queryOrderVolume", args, "h1"));
        assertEquals(TerminationReason.LOOP_REPEATED_CALL, d.recordToolCall("queryOrderVolume", args, "h1"));
    }

    @Test
    void 同参数但证据前进_不误杀() {
        LoopDetector d = new LoopDetector(3, 6);
        Map<String, String> args = window("2026-08-01~2026-08-07");
        assertNull(d.recordToolCall("queryOrderVolume", args, "h1"));
        assertNull(d.recordToolCall("queryOrderVolume", args, "h2"));
        assertNull(d.recordToolCall("queryOrderVolume", args, "h3"));
    }

    @Test
    void 连续同工具不同参数_不触发() {
        LoopDetector d = new LoopDetector(3, 6);
        assertNull(d.recordToolCall("queryOrderVolume", window("2026-08-01~2026-08-07"), "h1"));
        assertNull(d.recordToolCall("queryOrderVolume", window("2026-08-02~2026-08-08"), "h2"));
        assertNull(d.recordToolCall("queryOrderVolume", window("2026-08-03~2026-08-09"), "h3"));
    }

    @Test
    void 空转THINK步_证据链无前进_触发循环终止() {
        LoopDetector d = new LoopDetector(3, 3);
        assertNull(d.recordStep("same"));
        assertNull(d.recordStep("same"));
        assertEquals(TerminationReason.LOOP_NO_PROGRESS, d.recordStep("same"));
    }

    // ---- PolicyGuard：deny-by-default ----

    @Test
    void 授权工具_放行() {
        PolicyGuard guard = new PolicyGuard(AgentToolBinder.build(null, null, null));
        assertEquals(true, guard.evaluate("queryOrderVolume", window("2026-08-01~2026-08-07")).allowed());
        assertEquals(true, guard.evaluate("paymentSuccessRate", window("2026-08-01~2026-08-07")).allowed());
        assertEquals(true, guard.evaluate("contentInteraction", window("2026-08-01~2026-08-07")).allowed());
        assertEquals(true, guard.evaluate("baselineWindow", window("2026-08-01~2026-08-07")).allowed());
    }

    @Test
    void 非授权工具_拒绝() {
        PolicyDecision d = new PolicyGuard(AgentToolBinder.build(null, null, null)).evaluate("dropDatabase", window("2026-08-01~2026-08-07"));
        assertEquals(false, d.allowed());
        assertEquals(false, d.requiresApproval());
    }

    @Test
    void L3工具_需人工审批() {
        // M11：dlq.redeliver 已绑定执行器（真实审批工具）→ requiresApproval；
        // 预留工具（无执行器）→ deny（未开放，不挂起）
        PolicyGuard guard = new PolicyGuard(AgentToolBinder.build(null, null, null,
                (msgId, group) -> "fake-redeliver"));
        PolicyDecision d1 = guard.evaluate("dlq.redeliver",
                Map.of("msgId", "0123456789abcdef0123456789abcdef", "consumerGroup", "cart-sync-group"));
        assertEquals(true, d1.requiresApproval(), "已开放 L3 应要求审批: " + d1.reason());
        assertEquals(false, d1.allowed());
        PolicyDecision bad = guard.evaluate("dlq.redeliver", Map.of("msgId", "x", "consumerGroup", "g"));
        assertEquals(false, bad.allowed(), "非法参数应拒绝（不浪费审批）: " + bad.reason());
        assertEquals(false, bad.requiresApproval());
        // 无执行器的 L3 预留工具 → deny（非审批）
        PolicyGuard noDlq = new PolicyGuard(AgentToolBinder.build(null, null, null));
        assertEquals(false, noDlq.evaluate("dlq.redeliver", Map.of()).allowed());
        assertEquals(false, noDlq.evaluate("dlq.redeliver", Map.of()).requiresApproval());
        assertEquals(false, noDlq.evaluate("service.restart", null).requiresApproval());
        assertEquals(false, noDlq.evaluate("order.refund", null).requiresApproval());
    }

    @Test
    void window参数校验() {
        assertNull(ToolParamValidators.validateWindow("2026-08-01~2026-08-07"));
        assertNull(ToolParamValidators.validateWindow("2026-08-01~2026-08-31"));
        assertEquals("window 格式必须为 yyyy-MM-dd~yyyy-MM-dd",
                ToolParamValidators.validateWindow("20260801~20260807"));
        assertEquals("window 起始日期不能晚于结束日期",
                ToolParamValidators.validateWindow("2026-08-07~2026-08-01"));
        assertEquals("window 跨度不能超过 31 天",
                ToolParamValidators.validateWindow("2026-08-01~2026-09-05"));
        assertEquals("window 必填（yyyy-MM-dd~yyyy-MM-dd）",
                ToolParamValidators.validateWindow(""));
    }

    // ---- ToolResultRegistry：存在性校验数据源 ----

    @Test
    void 登记后可校验存在性() {
        ToolResultRegistry registry = new ToolResultRegistry();
        String evId = registry.register("queryOrderVolume", window("2026-08-01~2026-08-07"), "volume=61");
        assertEquals(true, registry.contains(evId));
        assertEquals(false, registry.contains("ev_fake"));
        assertEquals("volume=61", registry.get(evId).orElseThrow().result());
    }

    // ---- EvidenceChain：hash 前进 = 新证据（语义去重） ----

    @Test
    void 证据链hash随新证据变化() {
        EvidenceChain chain = new EvidenceChain();
        String h1 = chain.hash();
        chain.add("ev_1", "queryOrderVolume", "2026-08-01~2026-08-07", "volume=61");
        String h2 = chain.hash();
        chain.add("ev_2", "paymentSuccessRate", "2026-08-01~2026-08-07", "rate=0.5");
        String h3 = chain.hash();
        assertEquals(false, h1.equals(h2));
        assertEquals(false, h2.equals(h3));
        assertEquals(2, chain.size());
    }

    @Test
    void 重复内容不同evidenceId_不算新证据() {
        EvidenceChain chain = new EvidenceChain();
        chain.add("ev_1", "queryOrderVolume", "2026-08-01~2026-08-07", "volume=61");
        String h1 = chain.hash();
        chain.add("ev_2", "queryOrderVolume", "2026-08-01~2026-08-07", "volume=61");
        assertEquals(h1, chain.hash());
        assertEquals(1, chain.size());
    }

    @Test
    void 不同窗口同内容_是新证据() {
        EvidenceChain chain = new EvidenceChain();
        chain.add("ev_1", "queryOrderVolume", "2026-08-01~2026-08-07", "volume=61");
        String h1 = chain.hash();
        chain.add("ev_2", "queryOrderVolume", "2026-08-08~2026-08-14", "volume=61");
        assertEquals(false, h1.equals(chain.hash()));
        assertEquals(2, chain.size());
    }

    @Test
    void 重复evidenceId_不重复入链() {
        EvidenceChain chain = new EvidenceChain();
        chain.add("ev_1", "queryOrderVolume", "w", "volume=61");
        chain.add("ev_1", "queryOrderVolume", "w", "volume=61");
        assertEquals(1, chain.size());
    }

    // ---- AgentRun：状态机 ----

    @Test
    void run终止与取消() {
        AgentRun run = new AgentRun("r1", "为什么订单量下降", AgentBudget.defaults());
        run.terminate(TerminationReason.BUDGET_STEPS, "partial");
        assertEquals(RunStatus.PARTIAL, run.status());
        assertEquals(TerminationReason.BUDGET_STEPS, run.terminationReason());

        AgentRun run2 = new AgentRun("r2", "q", AgentBudget.defaults());
        run2.terminate(TerminationReason.CANCELLED, "已取消");
        assertEquals(RunStatus.CANCELLED, run2.status());
        assertEquals(TerminationReason.CANCELLED, run2.terminationReason());
    }
}
