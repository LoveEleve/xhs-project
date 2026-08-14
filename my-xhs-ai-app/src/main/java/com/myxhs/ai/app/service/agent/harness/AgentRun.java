package com.myxhs.ai.app.service.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次诊断 Run（设计 §7 Step 粒度 checkpoint 的聚合载体）。
 * 状态机（RunStatus）：RUNNING → SUCCEEDED / PARTIAL / FAILED / CANCELLED。
 * 每步落 AgentStep 记录；终止时带 TerminationReason；partial 结果 = 已收集证据原样返回，不假装全成。
 */
public class AgentRun {

    private final String runId;
    private final String query;
    private final AgentBudget budget;
    private final List<AgentStep> steps = new ArrayList<>();
    private final EvidenceChain evidenceChain = new EvidenceChain();
    private final ToolResultRegistry registry = new ToolResultRegistry();

    private volatile RunStatus status = RunStatus.RUNNING;
    private volatile TerminationReason terminationReason;
    private volatile String finalAnswer;
    private volatile Instant startedAt = Instant.now();
    private volatile Instant endedAt;

    public AgentRun(String runId, String query, AgentBudget budget) {
        this.runId = runId;
        this.query = query;
        this.budget = budget;
    }

    public void recordStep(AgentStep step) {
        steps.add(step);
    }

    public void terminate(TerminationReason reason, String finalAnswer) {
        this.status = switch (reason) {
            case COMPLETED -> RunStatus.SUCCEEDED;
            case BUDGET_STEPS, BUDGET_TOKENS, BUDGET_COST, LOOP_REPEATED_CALL, LOOP_NO_PROGRESS,
                 POLICY_EXHAUSTED, EVIDENCE_INVALID -> RunStatus.PARTIAL;
            case MODEL_UNAVAILABLE -> RunStatus.FAILED;
            case CANCELLED -> RunStatus.CANCELLED;
        };
        this.terminationReason = reason;
        this.finalAnswer = finalAnswer;
        this.endedAt = Instant.now();
    }

    /** 用户取消（设计 §6.1#6）：仅 V1 同步执行前的入口，预留 D5 异步化 */
    public void cancel() {
        this.status = RunStatus.CANCELLED;
        this.terminationReason = null;
        this.finalAnswer = null;
        this.endedAt = Instant.now();
    }

    public String runId() {
        return runId;
    }

    public String query() {
        return query;
    }

    public AgentBudget budget() {
        return budget;
    }

    public List<AgentStep> steps() {
        return List.copyOf(steps);
    }

    public EvidenceChain evidenceChain() {
        return evidenceChain;
    }

    public ToolResultRegistry registry() {
        return registry;
    }

    public RunStatus status() {
        return status;
    }

    public TerminationReason terminationReason() {
        return terminationReason;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }
}
