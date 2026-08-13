package com.myxhs.ai.app.service.agent.harness;

/**
 * 预算三重封顶（设计 §3）：步骤数 / Token / 成本。由 LoopCtrl 单调累加。
 */
public record AgentBudget(int maxSteps, long maxTokens, double maxCost) {

    public static final int DEFAULT_MAX_STEPS = 15;
    public static final long DEFAULT_MAX_TOKENS = 30_000;
    public static final double DEFAULT_MAX_COST = 1.0;

    public static AgentBudget defaults() {
        return new AgentBudget(DEFAULT_MAX_STEPS, DEFAULT_MAX_TOKENS, DEFAULT_MAX_COST);
    }

    public AgentBudget {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps 必须 > 0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须 > 0");
        }
        if (maxCost <= 0) {
            throw new IllegalArgumentException("maxCost 必须 > 0");
        }
    }
}
