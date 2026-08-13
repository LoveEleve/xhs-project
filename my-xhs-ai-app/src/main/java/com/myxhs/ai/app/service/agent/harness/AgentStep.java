package com.myxhs.ai.app.service.agent.harness;

import java.time.Instant;
import java.util.List;

/**
 * Step 记录（设计 §2 checkpoint 粒度）：每完成一个状态落一条。
 * 语义：一步（stepNumber）含多个状态——THINK(决策) + TOOL(执行) 同号，如 1,1,2,2；
 * state：THINK / POLICY_DENIED / TOOL / ANSWER
 * toolResult：TOOL 步=工具原始结果；POLICY_DENIED 步=拒绝原因（无其他状态用该字段）。
 */
public record AgentStep(
        int stepNumber,
        String state,
        AgentDecision decision,
        String toolResult,
        List<String> evidenceRefs,
        int tokensUsed,
        Instant createdAt) {

    public static AgentStep think(int stepNumber, AgentDecision decision, int tokensUsed) {
        return new AgentStep(stepNumber, "THINK", decision, null, null, tokensUsed, Instant.now());
    }

    /**
     * 策略拒绝步：toolResult 字段承载拒绝原因（语义混用，字段注释如下）。
     * 该步不注册证据、不计 token。
     */
    public static AgentStep policyDenied(int stepNumber, AgentDecision decision, String reason) {
        return new AgentStep(stepNumber, "POLICY_DENIED", decision, reason, null, 0, Instant.now());
    }

    public static AgentStep tool(int stepNumber, AgentDecision decision, String toolResult,
                                 List<String> evidenceRefs) {
        return new AgentStep(stepNumber, "TOOL", decision, toolResult, evidenceRefs, 0, Instant.now());
    }

    public static AgentStep answer(int stepNumber, AgentDecision decision) {
        return new AgentStep(stepNumber, "ANSWER", decision, null,
                decision == null ? null : decision.evidenceRefs(), 0, Instant.now());
    }
}
