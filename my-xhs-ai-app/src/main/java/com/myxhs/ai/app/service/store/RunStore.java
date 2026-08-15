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
            long tokensTotal, double costEst, String finalAnswer,
            Instant startedAt, Instant endedAt, Instant lastActivityAt) {
    }

    record StepRecord(
            long id, String runId, int stepNo, String state,
            String decisionJson, String toolResult, String evidenceIds,
            String messagesSnapshot, long tokensUsed, Instant createdAt) {
    }

    void createRun(String runId, String userId, String sessionId, String query,
                   String budgetJson, String versionsJson);

    void saveStep(String runId, AgentStep step, String messagesSnapshot);

    void updateRunStatus(String runId, String status, String terminationReason,
                         long tokensTotal, double costEst);

    /** 终态答案落库（M8-4 历史追溯：内存 TTL/重启后仍可查到最终答案） */
    void updateFinalAnswer(String runId, String finalAnswer);

    Optional<RunRecord> loadRun(String runId);

    List<StepRecord> loadSteps(String runId);

    /** 崩溃恢复：最后一个有 messages 快照的 step */
    Optional<StepRecord> lastCheckpoint(String runId);

    /** 崩溃判定：RUNNING 且最后活动时间早于 before（心跳超时视为崩溃） */
    List<String> findRunningStale(Instant before);

    /** 原子认领（双实例防双份执行）：仅 RUNNING 可认领并刷新心跳；返回影响行数（0=已被认领） */
    int claimRunning(String runId);
}
