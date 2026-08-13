package com.myxhs.ai.app.service.agent.harness;

/**
 * 预算控制器（设计 §3 + §2）：单调累加 steps/tokens/cost，另计 policy 拒绝次数。
 * 每次 THINK/TOOL 前调用 checkBeforeStep()，达到任一上限即返回终止原因（不靠事后）。
 * 模型连续 N 次请求非授权工具（POLICY_EXHAUSTED 上限，默认 3）也在此判定。
 */
public class LoopCtrl {

    private static final int DEFAULT_MAX_POLICY_DENIED = 3;

    private final AgentBudget budget;
    private final int maxPolicyDenied;
    private int steps;
    private long tokens;
    private double cost;
    private int policyDeniedCount;

    public LoopCtrl(AgentBudget budget) {
        this(budget, DEFAULT_MAX_POLICY_DENIED);
    }

    public LoopCtrl(AgentBudget budget, int maxPolicyDenied) {
        if (maxPolicyDenied <= 0) {
            throw new IllegalArgumentException("maxPolicyDenied 必须 > 0");
        }
        this.budget = budget;
        this.maxPolicyDenied = maxPolicyDenied;
    }

    public AgentBudget budget() {
        return budget;
    }

    public int steps() {
        return steps;
    }

    public long tokens() {
        return tokens;
    }

    public double cost() {
        return cost;
    }

    public int policyDeniedCount() {
        return policyDeniedCount;
    }

    /** 每步（THINK/TOOL 前）检查；超限返回终止原因，否则 null */
    public TerminationReason checkBeforeStep() {
        if (policyDeniedCount >= maxPolicyDenied) {
            return TerminationReason.POLICY_EXHAUSTED;
        }
        if (steps >= budget.maxSteps()) {
            return TerminationReason.BUDGET_STEPS;
        }
        if (tokens >= budget.maxTokens()) {
            return TerminationReason.BUDGET_TOKENS;
        }
        if (cost >= budget.maxCost()) {
            return TerminationReason.BUDGET_COST;
        }
        return null;
    }

    public void recordStep(int tokensUsed) {
        steps++;
        tokens += Math.max(0, tokensUsed);
    }

    public void recordCost(double increment) {
        cost += Math.max(0, increment);
    }

    public void recordPolicyDenied() {
        policyDeniedCount++;
    }
}
