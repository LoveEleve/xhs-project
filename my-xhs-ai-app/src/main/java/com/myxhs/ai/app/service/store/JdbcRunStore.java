package com.myxhs.ai.app.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JdbcRunStore（M5）：my_xhs_ai 库 ai_run/ai_step 两表。
 * Step 粒度 checkpoint：saveStep 落 messages 快照，崩溃后 lastCheckpoint 重建上下文重放。
 */
public class JdbcRunStore implements RunStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public JdbcRunStore(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public void createRun(String runId, String userId, String sessionId, String query,
                          String budgetJson, String versionsJson) {
        jdbc.update("INSERT INTO ai_run (run_id, user_id, session_id, query, status,"
                        + " budget_json, versions_json, started_at) VALUES (?,?,?,?,?,?,?,?)",
                runId, userId, sessionId, query, "RUNNING", budgetJson, versionsJson,
                Timestamp.from(Instant.now()));
    }

    @Override
    public void saveStep(String runId, AgentStep step, String messagesSnapshot) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_step (run_id, step_no, state, decision_json, tool_result,"
                        + " evidence_ids, messages_snapshot, tokens_used, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                runId, step.stepNumber(), step.state().name(),
                json(step.decision()), step.toolResult(),
                step.evidenceRefs() == null ? null : String.join(",", step.evidenceRefs()),
                messagesSnapshot, step.tokensUsed(), Timestamp.from(now));
        // 心跳：更新最后活动时间（崩溃恢复判定：RUNNING 且 last_activity_at 超时）
        jdbc.update("UPDATE ai_run SET last_activity_at=? WHERE run_id=?",
                Timestamp.from(now), runId);
    }

    @Override
    public void updateRunStatus(String runId, String status, String terminationReason,
                                long tokensTotal, double costEst) {
        jdbc.update("UPDATE ai_run SET status=?, termination_reason=?, tokens_total=?,"
                        + " cost_est=?, ended_at=?, last_activity_at=? WHERE run_id=?",
                status, terminationReason, tokensTotal, costEst,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), runId);
    }

    @Override
    public void updateFinalAnswer(String runId, String finalAnswer) {
        jdbc.update("UPDATE ai_run SET final_answer=? WHERE run_id=?",
                finalAnswer, runId);
    }

    @Override
    public void updateSessionId(String runId, String sessionId) {
        jdbc.update("UPDATE ai_run SET session_id=? WHERE run_id=?",
                sessionId, runId);
    }

    @Override
    public Optional<RunRecord> loadRun(String runId) {
        List<RunRecord> rows = jdbc.query("SELECT * FROM ai_run WHERE run_id=?",
                (rs, i) -> toRun(rs), runId);
        return rows.stream().findFirst();
    }

    @Override
    public List<StepRecord> loadSteps(String runId) {
        return jdbc.query("SELECT * FROM ai_step WHERE run_id=? ORDER BY step_no, id",
                (rs, i) -> toStep(rs), runId);
    }

    @Override
    public Optional<StepRecord> lastCheckpoint(String runId) {
        List<StepRecord> rows = jdbc.query(
                "SELECT * FROM ai_step WHERE run_id=? AND messages_snapshot IS NOT NULL"
                        + " ORDER BY id DESC LIMIT 1",
                (rs, i) -> toStep(rs), runId);
        return rows.stream().findFirst();
    }

    @Override
    public List<String> findRunningStale(Instant before) {
        return jdbc.query("SELECT run_id FROM ai_run WHERE status='RUNNING'"
                        + " AND (last_activity_at IS NULL OR last_activity_at < ?)",
                (rs, i) -> rs.getString("run_id"), Timestamp.from(before));
    }

    @Override
    public int claimRunning(String runId) {
        return jdbc.update("UPDATE ai_run SET last_activity_at=? WHERE run_id=? AND status='RUNNING'",
                Timestamp.from(Instant.now()), runId);
    }

    private RunRecord toRun(ResultSet rs) throws SQLException {
        return new RunRecord(
                rs.getString("run_id"), rs.getString("user_id"), rs.getString("session_id"),
                rs.getString("query"), rs.getString("status"), rs.getString("termination_reason"),
                rs.getString("budget_json"), rs.getString("versions_json"),
                rs.getLong("tokens_total"), rs.getDouble("cost_est"), rs.getString("final_answer"),
                ts(rs.getTimestamp("started_at")), ts(rs.getTimestamp("ended_at")),
                ts(rs.getTimestamp("last_activity_at")));
    }

    private StepRecord toStep(ResultSet rs) throws SQLException {
        return new StepRecord(
                rs.getLong("id"), rs.getString("run_id"), rs.getInt("step_no"), rs.getString("state"),
                rs.getString("decision_json"), rs.getString("tool_result"), rs.getString("evidence_ids"),
                rs.getString("messages_snapshot"), rs.getLong("tokens_used"), ts(rs.getTimestamp("created_at")));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private String json(Object o) {
        try {
            return o == null ? null : om.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[runstore] 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
