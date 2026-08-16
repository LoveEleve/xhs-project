package com.myxhs.ai.app.service.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.store.JdbcRunStore;
import com.myxhs.ai.app.service.store.RunStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M11 HITL 审批闭环（H2 + fake 模型）：
 *  - L3 请求 → WAITING_APPROVAL 挂起（无执行/无证据/approval_json PENDING）
 *  - 审批通过 → resume 直接执行被审批工具 → 证据登记 → 继续完成
 *  - 审批恢复防重复执行（EXECUTED 标记）
 */
class HitlApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EV = Pattern.compile("证据 id=(ev_\\w+)");
    private static final String MSG_ID = "0123456789abcdef0123456789abcdef";

    private JdbcTemplate jdbc;
    private JdbcRunStore store;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:hitl;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_run (run_id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(64),"
                + " session_id VARCHAR(64), query TEXT NOT NULL, status VARCHAR(16) NOT NULL,"
                + " termination_reason VARCHAR(32), budget_json TEXT, versions_json TEXT,"
                + " tokens_total BIGINT DEFAULT 0, cost_est DOUBLE DEFAULT 0, final_answer MEDIUMTEXT,"
                + " approval_json TEXT, started_at DATETIME(3), ended_at DATETIME(3), last_activity_at DATETIME(3))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ai_step (id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(32),"
                + " step_no INT, state VARCHAR(24), decision_json TEXT, tool_result MEDIUMTEXT,"
                + " evidence_ids VARCHAR(512), messages_snapshot MEDIUMTEXT, tokens_used BIGINT DEFAULT 0, created_at DATETIME(3))");
        jdbc.update("DELETE FROM ai_step; DELETE FROM ai_run;");
        store = new JdbcRunStore(jdbc, MAPPER);
    }

    /** fake 模型：首轮请求 dlq.redeliver；之后（工具结果在上下文中）回答 */
    private AgentHarness harnessWithDlq(String dlqResult) {
        Function<List<String>, String> responder = texts -> {
            String all = String.join("", texts);
            if (all.contains("dlq.redeliver 结果")) {
                String ev = lastEvId(texts);
                return "{\"action\":\"ANSWER\",\"conclusion\":\"已重投消息\""
                        + (ev == null ? "" : ",\"evidenceRefs\":[\"" + ev + "\"]")
                        + ",\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            }
            return "{\"action\":\"TOOL_CALL\",\"tool\":\"dlq.redeliver\","
                    + "\"args\":{\"msgId\":\"" + MSG_ID + "\",\"consumerGroup\":\"cart-sync-group\"},"
                    + "\"reasoning\":\"死信重投需审批\"}";
        };
        ChatModel model = new FakeChatModel(responder);
        return new AgentHarness(model, null, null, null,
                (msgId, group) -> dlqResult, MAPPER, AgentBudget.defaults(), 0.002, 2, store, "fake", 400);
    }

    @Test
    void L3请求_挂起待审批() {
        AgentHarness h = harnessWithDlq("{\"status\":\"ok\",\"tool\":\"dlq.redeliver\"}");
        AgentRun run = h.run("死信重投", AgentBudget.defaults(), null);
        // 挂起：不执行、无证据、状态 WAITING_APPROVAL
        assertEquals(RunStatus.WAITING_APPROVAL, run.status(), "L3 应挂起");
        assertTrue(run.evidenceChain().size() == 0, "挂起不得执行/登记证据");
        assertNotNull(run.pendingApproval(), "应记录待审批参数");
        assertEquals(MSG_ID, run.pendingApproval().get("msgId"));
        // approval_json 落库 PENDING
        String approval = store.loadApproval(run.runId()).orElseThrow();
        assertTrue(approval.contains("\"status\":\"PENDING\""), approval);
        assertTrue(approval.contains("dlq.redeliver"), approval);
        // store 状态 WAITING_APPROVAL（崩溃恢复 claimRunning 只认 RUNNING → 不会误恢复）
        assertEquals("WAITING_APPROVAL", store.loadRun(run.runId()).orElseThrow().status());
    }

    @Test
    void 审批通过_resume执行并完成() {
        AgentHarness h = harnessWithDlq("{\"status\":\"ok\",\"tool\":\"dlq.redeliver\",\"msgId\":\"" + MSG_ID + "\"}");
        AgentRun run = h.run("死信重投", AgentBudget.defaults(), null);
        String runId = run.runId();
        assertEquals(RunStatus.WAITING_APPROVAL, run.status());

        // 模拟审批通过（RunManager.approve 做原子认领 + 审计；此处直接置 APPROVED + RUNNING）
        String approval = store.loadApproval(runId).orElseThrow()
                .replace("\"status\":\"PENDING\"", "\"status\":\"APPROVED\"")
                .replace("}", ",\"approver\":\"ops1\",\"reason\":\"确认重投\",\"decidedAt\":\"2026-08-15T00:00:00Z\"}");
        store.updateApproval(runId, approval);
        assertEquals(1, store.claimApproval(runId), "原子认领应成功");

        AgentRun resumed = h.resume(runId, null, null);
        assertEquals(RunStatus.SUCCEEDED, resumed.status(), "审批恢复后应完成");
        // 证据链包含被审批工具的登记
        assertTrue(resumed.evidenceChain().entries().stream()
                        .anyMatch(e -> e.tool().equals("dlq.redeliver")),
                "审批执行的工具应登记证据: " + resumed.evidenceChain().entries());
        assertTrue(resumed.finalAnswer().contains("已重投消息"), resumed.finalAnswer());
        // EXECUTED 标记（防重复执行）
        String after = store.loadApproval(runId).orElseThrow();
        assertTrue(after.contains("\"status\":\"EXECUTED\""), "执行后应标记 EXECUTED: " + after);
    }

    @Test
    void 非法参数_不挂起直接拒绝() {
        // 非法 msgId → PolicyGuard deny（不进入审批挂起）
        Function<List<String>, String> responder = texts ->
                "{\"action\":\"TOOL_CALL\",\"tool\":\"dlq.redeliver\","
                        + "\"args\":{\"msgId\":\"bad-id\",\"consumerGroup\":\"cart-sync-group\"},\"reasoning\":\"\"}";
        AgentHarness h = new AgentHarness(new FakeChatModel(responder), null, null, null,
                (msgId, group) -> "ok", MAPPER, AgentBudget.defaults(), 0.002, 2, store, "fake", 400);
        AgentRun run = h.run("重投", AgentBudget.defaults(), null);
        // 模型连续请求非法参数：POLICY_EXHAUSTED 终止（不挂起）
        assertTrue(run.status() == RunStatus.PARTIAL || run.status() == RunStatus.FAILED, run.status().name());
        assertTrue(store.loadApproval(run.runId()).isEmpty(), "非法参数不得进入审批: " + store.loadApproval(run.runId()));
    }

    @Test
    void M13_审批恢复保留画像() {
        // Ops 画像挂起 → resume 后画像保留（工具子集过滤不丢失，P1 回归）
        AgentHarness h = harnessWithDlq("{\"status\":\"ok\",\"tool\":\"dlq.redeliver\"}");
        AgentRun run = h.run("MQ 死信积压了，帮我重投死信消息",
                com.myxhs.ai.app.service.agent.profile.AgentProfiles.OPS);
        String runId = run.runId();
        assertEquals(RunStatus.WAITING_APPROVAL, run.status());
        // versionsJson 含 profile（resume 依赖）
        var rec = store.loadRun(runId).orElseThrow();
        assertTrue(rec.versionsJson().contains("\"profile\":\"OPS\""), rec.versionsJson());

        String approval = store.loadApproval(runId).orElseThrow()
                .replace("\"status\":\"PENDING\"", "\"status\":\"APPROVED\"");
        store.updateApproval(runId, approval);
        store.claimApproval(runId);

        AgentRun resumed = h.resume(runId, null, null);
        assertEquals(com.myxhs.ai.app.service.agent.profile.AgentProfiles.OPS.id(),
                resumed.profile().id(), "resume 应恢复画像");
        assertEquals(RunStatus.SUCCEEDED, resumed.status());
    }

    private static String lastEvId(List<String> texts) {
        String found = null;
        for (String t : texts) {
            Matcher m = EV.matcher(t);
            if (m.find()) {
                found = m.group(1);
            }
        }
        return found;
    }

    private static final class FakeChatModel implements ChatModel {
        private final Function<List<String>, String> responder;

        FakeChatModel(Function<List<String>, String> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            List<String> texts = request.messages().stream()
                    .map(m -> m instanceof AiMessage a ? a.text()
                            : m instanceof dev.langchain4j.data.message.UserMessage u ? u.singleText()
                            : m instanceof dev.langchain4j.data.message.SystemMessage s ? s.text() : "")
                    .toList();
            String json = responder.apply(texts);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(5, 5)).modelName("fake").build())
                    .build();
        }
    }
}
