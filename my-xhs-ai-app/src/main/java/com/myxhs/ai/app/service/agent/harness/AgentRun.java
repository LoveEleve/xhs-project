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
    private volatile com.myxhs.ai.app.service.trace.TraceDiagnosisResult traceDiagnosis;
    /** M13：Agent 画像（工具子集/prompt 变体；FULL=单 Agent 全量）。per-run 字段，非单例（并发安全） */
    private volatile com.myxhs.ai.app.service.agent.profile.AgentProfile profile;

    /** M11 HITL：挂起待审批的工具（tool/args；审批后 resume 据此执行，不设终态） */
    private volatile String pendingTool;
    private volatile java.util.Map<String, String> pendingApproval;

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
            case APPROVAL_REJECTED, CANCELLED -> RunStatus.CANCELLED;
        };
        this.terminationReason = reason;
        this.finalAnswer = finalAnswer;
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

    /** M11 HITL：挂起待审批（status=WAITING_APPROVAL，不设终态/endedAt；审批后 resume 重建新 run 对象） */
    public void flagWaitingApproval(String tool, java.util.Map<String, String> approvalArgs) {
        this.status = RunStatus.WAITING_APPROVAL;
        this.pendingTool = tool;
        this.pendingApproval = approvalArgs;
    }

    public String pendingTool() {
        return pendingTool;
    }

    /** M13：设置画像（null=未指定，PolicyGuard 不做子集过滤） */
    public void setProfile(com.myxhs.ai.app.service.agent.profile.AgentProfile profile) {
        this.profile = profile;
    }

    public com.myxhs.ai.app.service.agent.profile.AgentProfile profile() {
        return profile;
    }

    public java.util.Map<String, String> pendingApproval() {
        return pendingApproval;
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

    public void setTraceDiagnosis(com.myxhs.ai.app.service.trace.TraceDiagnosisResult traceDiagnosis) {
        this.traceDiagnosis = traceDiagnosis;
    }

    public com.myxhs.ai.app.service.trace.TraceDiagnosisResult traceDiagnosis() {
        return traceDiagnosis;
    }
}
