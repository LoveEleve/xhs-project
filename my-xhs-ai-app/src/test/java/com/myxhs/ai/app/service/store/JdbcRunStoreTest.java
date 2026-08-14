package com.myxhs.ai.app.service.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentDecision;
import com.myxhs.ai.app.service.agent.harness.AgentStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcRunStore 单测（H2，MySQL 兼容模式）。
 * 覆盖：创建 run / 落 step 含 messages 快照 / 终态更新 / 加载 / lastCheckpoint。
 */
class JdbcRunStoreTest {

    private JdbcTemplate jdbc;
    private JdbcRunStore store;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:runstore;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_run (
                  run_id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(64), session_id VARCHAR(64),
                  query TEXT NOT NULL, status VARCHAR(16) NOT NULL, termination_reason VARCHAR(32),
                  budget_json TEXT, versions_json TEXT, tokens_total BIGINT DEFAULT 0,
                  tokens_out BIGINT DEFAULT 0, cost_est DOUBLE DEFAULT 0,
                  started_at DATETIME(3), ended_at DATETIME(3), last_activity_at DATETIME(3)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_step (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(32), step_no INT, state VARCHAR(24),
                  decision_json TEXT, tool_result MEDIUMTEXT, evidence_ids VARCHAR(512),
                  messages_snapshot MEDIUMTEXT, created_at DATETIME(3)
                )""");
        jdbc.update("DELETE FROM ai_run; DELETE FROM ai_step;");
        store = new JdbcRunStore(jdbc, new ObjectMapper());
    }

    @Test
    void 创建run并落终态() {
        store.createRun("run_abc", "u1", null, "为什么订单量下降",
                "{\"maxSteps\":15}", "{\"model\":\"m1\",\"prompt\":\"v1\",\"tools\":13}");
        store.updateRunStatus("run_abc", "SUCCEEDED", "COMPLETED", 1200, 0.5);

        var run = store.loadRun("run_abc").orElseThrow();
        assertEquals("SUCCEEDED", run.status());
        assertEquals("COMPLETED", run.terminationReason());
        assertEquals(1200, run.tokensTotal());
        assertEquals("为什么订单量下降", run.query());
    }

    @Test
    void 落step含messages快照并可取最后checkpoint() {
        store.createRun("run_1", "anonymous", null, "q", "{}", "{}");
        AgentStep s1 = AgentStep.think(1, AgentDecision.toolCall("queryOrderVolume",
                Map.of("window", "2026-08-01~2026-08-07"), "查窗口"), 100);
        AgentStep s2 = AgentStep.tool(1, AgentDecision.toolCall("queryOrderVolume",
                Map.of("window", "2026-08-01~2026-08-07"), "查窗口"), "volume=61", List.of("ev_1"));

        store.saveStep("run_1", s1, "[{\"type\":\"SYSTEM\",\"text\":\"你是...\"},{\"type\":\"USER\",\"text\":\"q\"}]");
        store.saveStep("run_1", s2, "[{\"type\":\"SYSTEM\",\"text\":\"你是...\"},{\"type\":\"USER\",\"text\":\"q\"},{\"type\":\"AI\",\"text\":\"dec\"}]");

        var steps = store.loadSteps("run_1");
        assertEquals(2, steps.size());
        assertEquals("TOOL", steps.get(1).state());
        assertEquals("volume=61", steps.get(1).toolResult());
        assertEquals("ev_1", steps.get(1).evidenceIds());

        var cp = store.lastCheckpoint("run_1").orElseThrow();
        assertTrue(cp.messagesSnapshot().contains("USER"));
        assertTrue(cp.messagesSnapshot().contains("dec"), "应含最后快照: " + cp.messagesSnapshot());
    }

    @Test
    void 无checkpoint时lastCheckpoint为空() {
        store.createRun("run_2", "anonymous", null, "q", "{}", "{}");
        assertTrue(store.lastCheckpoint("run_2").isEmpty());
    }
}
