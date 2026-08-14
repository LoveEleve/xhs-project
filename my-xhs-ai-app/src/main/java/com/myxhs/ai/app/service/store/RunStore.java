package com.myxhs.ai.app.service.store;

import com.myxhs.ai.app.service.agent.harness.AgentStep;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Run Store（M5 Durable Execution）：run/step 持久化接口。
 * 实现：JdbcRunStore（my_xhs_ai 库，写账号 myxhs_ai_rw）。
 * Step 粒度 checkpoint：每步落 messages 快照（JSON），崩溃后从最后未完成 step 重放。
 */
public interface RunStore {

    record RunRecord(
            String runId, String userId, String sessionId, String query,
            String status, String terminationReason,
            String budgetJson, String versionsJson,
            long tokensIn, long tokensOut, double costEst,
            Instant startedAt, Instant endedAt) {
    }

    record StepRecord(
            long id, String runId, int stepNo, String state,
            String decisionJson, String toolResult, String evidenceIds,
            String messagesSnapshot, Instant createdAt) {
    }

    void createRun(String runId, String userId, String sessionId, String query,
                   String budgetJson, String versionsJson);

    void saveStep(String runId, AgentStep step, String messagesSnapshot);

    void updateRunStatus(String runId, String status, String terminationReason,
                         long tokensIn, long tokensOut, double costEst);

    Optional<RunRecord> loadRun(String runId);

    List<StepRecord> loadSteps(String runId);

    /** 崩溃恢复：最后一个有 messages 快照的 step */
    Optional<StepRecord> lastCheckpoint(String runId);
}
