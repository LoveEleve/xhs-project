package com.myxhs.ai.app.controller;

import com.myxhs.ai.tools.MetricToolAccess;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步 Run 端点测试（mock 模型/工具）：POST 提交 → GET 查询 → SSE 订阅。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:runctl;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=direct",
        "MCP_API_KEY=",
        "myxhs.ai.llm.api-key=test-key",
        "spring.ai-datasource.url=jdbc:h2:mem:runctlai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa"
})
class RunControllerTest {

    private static final Pattern EV = Pattern.compile("证据 id=(ev_\\w+)");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatModel chatModel;

    @MockBean(name = "metricToolAccess")
    private MetricToolAccess metricToolAccess;

    @MockBean
    private com.myxhs.ai.tools.DlqRedeliverAccess dlqRedeliverAccess;

    @Autowired
    private com.myxhs.ai.app.service.store.RunStore runStore;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate aiJdbc;

    @org.junit.jupiter.api.BeforeEach
    void ensureTables() {
        aiJdbc.execute("CREATE TABLE IF NOT EXISTS ai_run (run_id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(64),"
                + " session_id VARCHAR(64), query TEXT NOT NULL, status VARCHAR(16) NOT NULL,"
                + " termination_reason VARCHAR(32), budget_json TEXT, versions_json TEXT,"
                + " tokens_total BIGINT DEFAULT 0, cost_est DOUBLE DEFAULT 0, final_answer MEDIUMTEXT,"
                + " approval_json TEXT, started_at DATETIME(3), ended_at DATETIME(3), last_activity_at DATETIME(3))");
        aiJdbc.execute("CREATE TABLE IF NOT EXISTS ai_step (id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(32),"
                + " step_no INT, state VARCHAR(24), decision_json TEXT, tool_result MEDIUMTEXT,"
                + " evidence_ids VARCHAR(512), messages_snapshot MEDIUMTEXT, tokens_used BIGINT DEFAULT 0, created_at DATETIME(3))");
        aiJdbc.execute("CREATE TABLE IF NOT EXISTS ai_conversation (conv_id VARCHAR(32) PRIMARY KEY,"
                + " user_id VARCHAR(64) NOT NULL DEFAULT 'anonymous', title VARCHAR(200), summary TEXT,"
                + " message_count INT DEFAULT 0, created_at DATETIME(3), last_activity_at DATETIME(3))");
        aiJdbc.execute("CREATE TABLE IF NOT EXISTS ai_message (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " conv_id VARCHAR(32) NOT NULL, role VARCHAR(16) NOT NULL, content MEDIUMTEXT NOT NULL,"
                + " run_id VARCHAR(32), refs_json TEXT, created_at DATETIME(3))");
        aiJdbc.update("DELETE FROM ai_message; DELETE FROM ai_conversation; DELETE FROM ai_step; DELETE FROM ai_run;");
    }

    private void stubModel() {
        when(metricToolAccess.queryOrderVolume(any())).thenReturn("volume=61");
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0, ChatRequest.class).messages();
            String all = msgs.stream().map(m -> m instanceof AiMessage a ? a.text()
                    : m instanceof UserMessage u ? u.singleText() : m.toString()).reduce("", String::concat);
            Matcher m = EV.matcher(all);
            String ev = m.find() ? m.group(1) : null;
            String json = ev == null
                    ? "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                    + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"查\"}"
                    : "{\"action\":\"ANSWER\",\"conclusion\":\"下单量为61\",\"evidenceRefs\":[\"" + ev
                    + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(5, 5)).modelName("fake").build())
                    .build();
        });
    }

    @Test
    void 提交查询订阅_全链路() throws Exception {
        stubModel();

        // 1. POST 提交 → 立即返回 runId
        MvcResult sub = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么订单量下降了\",\"userId\":\"ops1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = sub.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String runId = body.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        assertTrue(body.contains("RECEIVED"), "应返回 RECEIVED: " + body);

        // 2. GET 轮询直到完成
        MvcResult done = null;
        for (int i = 0; i < 50; i++) {
            MvcResult r = mockMvc.perform(get("/api/runs/" + runId))
                    .andExpect(status().isOk()).andReturn();
            String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (s.contains("SUCCEEDED")) {
                done = r;
                break;
            }
            Thread.sleep(100);
        }
        String view = done.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(view.contains("SUCCEEDED"), "应 SUCCEEDED: " + view);
        assertTrue(view.contains("下单量为61"), "应含答案: " + view);
        assertTrue(view.contains("COMPLETED"), "应含终止原因: " + view);

        // 3. SSE 订阅（已完成 → 缓冲补发全事件）
        MvcResult streamResult = mockMvc.perform(get("/api/runs/" + runId + "/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(streamResult))
                .andExpect(status().isOk())
                .andReturn();
        String stream = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(stream.contains("event:RUN_STARTED"), stream);
        assertTrue(stream.contains("event:COMPLETED"), stream);
    }

    @Test
    void 取消端点_返回CANCELLING() throws Exception {
        stubModel();
        MvcResult sub = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么订单量下降了\"}"))
                .andExpect(status().isOk()).andReturn();
        String runId = sub.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        // fake 模型极快——先取消（可能已成功→404 语义），再查状态
        MvcResult del = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/runs/" + runId))
                .andReturn();
        int code = del.getResponse().getStatus();
        if (code == 200) {
            assertTrue(del.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("CANCELLING"));
        }
    }

    @Test
    void run不存在_404() throws Exception {
        mockMvc.perform(get("/api/runs/run_nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 历史run_内存miss后store回退可查() throws Exception {
        // M8-4 可追溯闭环：内存 TTL/重启后，GET 回退 Run Store 重建视图
        String runId = "run_store_history_1";
        runStore.createRun(runId, "ops1", null, "为什么订单量下降了", "{}", "{}");
        runStore.saveStep(runId, com.myxhs.ai.app.service.agent.harness.AgentStep.think(1, null, 0), null);
        runStore.updateRunStatus(runId, "SUCCEEDED", "COMPLETED", 10, 0.01);
        runStore.updateFinalAnswer(runId, "历史答案：订单量从46降至8");

        MvcResult r = mockMvc.perform(get("/api/runs/" + runId))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("fromStore"), "应标记 fromStore 回退: " + body);
        assertTrue(body.contains("历史答案"), "应含落库的 finalAnswer: " + body);
        assertTrue(body.contains("SUCCEEDED"), body);
        assertTrue(body.contains("THINK"), "应含步骤: " + body.substring(0, Math.min(200, body.length())));
    }

    // ---------- M10 多轮会话 ----------

    private String submit(String message, String convId) throws Exception {
        String body = convId == null
                ? "{\"message\":\"" + message + "\",\"userId\":\"ops1\"}"
                : "{\"message\":\"" + message + "\",\"userId\":\"ops1\",\"conversationId\":\"" + convId + "\"}";
        MvcResult r = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void awaitSucceeded(String runId) throws Exception {
        for (int i = 0; i < 50; i++) {
            MvcResult r = mockMvc.perform(get("/api/runs/" + runId)).andExpect(status().isOk()).andReturn();
            if (r.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("SUCCEEDED")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("run 未完成: " + runId);
    }

    /** 模型调用捕获：文本（验证注入内容）+ 角色序列（验证注入条数/重复） */
    private record ModelCapture(StringBuilder text, StringBuilder roles) {
    }

    private ModelCapture stubModelWithCapture() {
        var captured = new StringBuilder();
        var roles = new StringBuilder();
        when(metricToolAccess.queryOrderVolume(any())).thenReturn("volume=61");
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0, ChatRequest.class).messages();
            String all = msgs.stream().map(RunControllerTest::textOf).reduce("", String::concat);
            captured.append("<<ROUND>>").append(all);
            roles.append("<<ROUND>>")
                    .append(msgs.stream().map(m -> m.type().name()).reduce("", (a, b) -> a + "," + b));
            Matcher m = EV.matcher(all);
            String ev = m.find() ? m.group(1) : null;
            String json = ev == null
                    ? "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                    + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"查\"}"
                    : "{\"action\":\"ANSWER\",\"conclusion\":\"下单量为61\",\"evidenceRefs\":[\"" + ev
                    + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(5, 5)).modelName("fake").build())
                    .build();
        });
        return new ModelCapture(captured, roles);
    }

    private static String textOf(ChatMessage m) {
        return m instanceof AiMessage a ? a.text()
                : m instanceof UserMessage u ? u.singleText()
                : m.toString();
    }

    @Test
    void 多轮会话_第二问注入上轮结论() throws Exception {
        ModelCapture capture = stubModelWithCapture();
        // 第一问：显式 convId
        String convId = "conv_flow_1";
        String r1 = submit("为什么订单量下降了", convId);
        assertTrue(r1.contains("\"conversationId\":\"conv_flow_1\""), r1);
        String run1 = r1.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        awaitSucceeded(run1);

        // 第二问：同 convId，历史结论应注入模型上下文
        String r2 = submit("那支付呢", convId);
        String run2 = r2.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        awaitSucceeded(run2);

        // 会话详情：2 轮 = 4 条消息 + summary 非空
        MvcResult cv = mockMvc.perform(get("/api/conversations/" + convId)).andExpect(status().isOk()).andReturn();
        String cbody = cv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(cbody.contains("\"messageCount\":4"), "应有 4 条消息: " + cbody);
        assertTrue(cbody.contains("summary"), cbody);
        assertTrue(cbody.contains("历史会话摘要") || cbody.contains("首问"), cbody);

        // 会话列表
        MvcResult lst = mockMvc.perform(get("/api/conversations?userId=ops1")).andExpect(status().isOk()).andReturn();
        assertTrue(lst.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("conv_flow_1"));

        // 第二问的模型调用必须收到第一问结论（无工具原文：不含"工具 queryOrderVolume 结果"）
        String allCalls = capture.text().toString();
        assertTrue(allCalls.contains("下单量为61"), "第二问应注入上轮结论: " + allCalls);
        // P0-2 回归：当前问题不得重复注入——Q2 首轮（尚无工具结果消息）中，
        // "用户问题："之前的 USER 消息必须恰 1 条（历史 Q1）。若 buildContext 晚于
        // appendUserMessage，当前问题会写进历史 → 之前出现 2 条 USER
        String[] textRounds = allCalls.split("<<ROUND>>");
        String[] roleRounds = capture.roles().toString().split("<<ROUND>>");
        for (int i = 1; i < textRounds.length; i++) {
            if (textRounds[i].contains("用户问题：那支付呢") && !textRounds[i].contains("工具 ")) {
                String round = roleRounds[i];
                int usersBeforeCurrent = round.substring(0, round.lastIndexOf("USER"))
                        .split("USER").length - 1;
                assertEquals(1, usersBeforeCurrent,
                        "当前问题不得重复注入，角色序列: " + round);
            }
        }
    }

    private static String captured2(StringBuilder sb) {
        return sb.toString();
    }

    @Test
    void 无convId提交_自动新建会话() throws Exception {
        stubModelWithCapture();
        String r = submit("为什么订单量下降了", null);
        assertTrue(r.contains("\"conversationId\":\""), "响应应带新 convId: " + r);
    }

    @Test
    void 同会话并发_409拒绝() throws Exception {
        when(metricToolAccess.queryOrderVolume(any())).thenReturn("volume=61");
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            // 首次调用 sleep 600ms 制造 RUNNING 窗口
            List<ChatMessage> msgs = inv.getArgument(0, ChatRequest.class).messages();
            Thread.sleep(600);
            Matcher m = EV.matcher(msgs.stream().map(RunControllerTest::textOf).reduce("", String::concat));
            String ev = m.find() ? m.group(1) : null;
            String json = ev == null
                    ? "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                    + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"查\"}"
                    : "{\"action\":\"ANSWER\",\"conclusion\":\"下单量为61\",\"evidenceRefs\":[\"" + ev
                    + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(5, 5)).modelName("fake").build())
                    .build();
        });
        submit("为什么订单量下降了", "conv_busy_1");
        // 第一个 run 仍在 RUNNING（mock 600ms）→ 同会话第二提交 409
        MvcResult r2 = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"那支付成功率呢\",\"userId\":\"ops1\",\"conversationId\":\"conv_busy_1\"}"))
                .andExpect(status().isConflict()).andReturn();
    }

    @Test
    void 会话锁_首个run完成后同会话可再提交() throws Exception {
        stubModel();
        String convId = "conv_rel_1";
        String r1 = submit("为什么订单量下降了", convId);
        String run1 = r1.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        awaitSucceeded(run1);
        // 锁已释放：同会话再次提交应 200（非 409）
        String r2 = submit("那支付成功率呢", convId);
        assertTrue(r2.contains("\"runId\":\""), r2);
        // run → 会话追溯：ai_run.session_id = convId
        Integer sessionIdCount = aiJdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_run WHERE run_id=? AND session_id=?",
                Integer.class, run1, convId);
        assertEquals(1, sessionIdCount.intValue(), "run 应关联会话 session_id");
    }

    @Test
    void 问候直答_写入会话消息() throws Exception {
        stubModel();
        String convId = "conv_greet_1";
        MvcResult r = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"userId\":\"ops1\",\"conversationId\":\"" + convId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("RECEIVED"), body);
        // 直答零步骤即终态：消息应已落库（user + assistant）
        MvcResult cv = mockMvc.perform(get("/api/conversations/" + convId)).andExpect(status().isOk()).andReturn();
        String cbody = cv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(cbody.contains("\"messageCount\":2"), "直答也应写会话消息: " + cbody);
    }

    @Test
    void Gateway集成_XUserIduer优先于body() throws Exception {
        stubModel();
        // header 注入 X-User-Id（网关统一鉴权场景）→ 会话归属 userId=header 值
        MvcResult r = mockMvc.perform(post("/api/runs")
                        .header("X-User-Id", "gw-user-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"userId\":\"body-user\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("RECEIVED"), body);
        String convId = body.replaceAll(".*\"conversationId\":\"([^\"]+)\".*", "$1");
        MvcResult cv = mockMvc.perform(get("/api/conversations/" + convId)).andExpect(status().isOk()).andReturn();
        assertTrue(cv.getResponse().getContentAsString(StandardCharsets.UTF_8)
                        .contains("\"userId\":\"gw-user-42\""),
                "X-User-Id header 应优先于 body userId");
    }

    private void stubModelForDlq() {
        when(dlqRedeliverAccess.redeliver(any(), any()))
                .thenReturn("{\"status\":\"ok\",\"tool\":\"dlq.redeliver\",\"msgId\":\"x\"}");
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0, ChatRequest.class).messages();
            String all = msgs.stream().map(RunControllerTest::textOf).reduce("", String::concat);
            Matcher m = EV.matcher(all);
            String ev = m.find() ? m.group(1) : null;
            String json;
            if (all.contains("dlq.redeliver 结果")) {
                json = "{\"action\":\"ANSWER\",\"conclusion\":\"已重投消息\",\"evidenceRefs\":[\""
                        + ev + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            } else {
                json = "{\"action\":\"TOOL_CALL\",\"tool\":\"dlq.redeliver\","
                        + "\"args\":{\"msgId\":\"0123456789abcdef0123456789abcdef\","
                        + "\"consumerGroup\":\"cart-sync-group\"},\"reasoning\":\"重投\"}";
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(5, 5)).modelName("fake").build())
                    .build();
        });
    }

    private String awaitStatus(String runId, String status) throws Exception {
        for (int i = 0; i < 50; i++) {
            MvcResult r = mockMvc.perform(get("/api/runs/" + runId)).andExpect(status().isOk()).andReturn();
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (body.contains("\"status\":\"" + status + "\"")) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("run " + runId + " 未达状态 " + status);
    }

    @Test
    void HITL_L3挂起_审批通过后执行完成() throws Exception {
        stubModelForDlq();
        String r1 = submit("MQ 死信积压了，帮我重投死信消息", "conv_hitl_1");
        String runId = r1.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        // 1. 挂起：GET 视图 WAITING_APPROVAL + pendingApproval
        String waiting = awaitStatus(runId, "WAITING_APPROVAL");
        assertTrue(waiting.contains("pendingApproval"), waiting);
        assertTrue(waiting.contains("dlq.redeliver"), waiting);
        // 工具未执行（mock 未被调用）
        org.mockito.Mockito.verify(dlqRedeliverAccess, org.mockito.Mockito.never())
                .redeliver(any(), any());

        // 2. 审批通过 → resume 执行 → 完成
        MvcResult ap = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\",\"approver\":\"ops1\",\"reason\":\"确认重投\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(ap.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("APPROVED_RUNNING"));

        String done = awaitStatus(runId, "SUCCEEDED");
        assertTrue(done.contains("已重投消息"), done);
        assertTrue(done.contains("dlq.redeliver"), "证据链应含被审批工具: " + done);
        // 审计落库
        org.mockito.Mockito.verify(dlqRedeliverAccess).redeliver(any(), any());
        // P1 回归：审批恢复后终态会话消息落库（挂起时未写，resume 完成后补写）
        MvcResult cv = mockMvc.perform(get("/api/conversations/conv_hitl_1")).andExpect(status().isOk()).andReturn();
        assertTrue(cv.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"messageCount\":2"),
                "审批恢复完成应写会话消息: " + cv.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void HITL_挂起期间同会话并发仍409() throws Exception {
        stubModelForDlq();
        String runId = submit("MQ 死信积压了，帮我重投死信消息", "conv_hitl_lock")
                .replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        awaitStatus(runId, "WAITING_APPROVAL");
        // P1 回归：挂起不是终态，会话锁不释放——同会话第二个 run 仍 409
        mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么订单量下降了\",\"userId\":\"ops1\",\"conversationId\":\"conv_hitl_lock\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void HITL_审批拒绝_终止并审计() throws Exception {
        stubModelForDlq();
        String runId = submit("MQ 死信积压了，帮我重投死信消息", "conv_hitl_2").replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        awaitStatus(runId, "WAITING_APPROVAL");

        MvcResult rj = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"approver\":\"ops2\",\"reason\":\"风险过高\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(rj.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("REJECTED_CANCELLED"));

        String done = awaitStatus(runId, "CANCELLED");
        assertTrue(done.contains("审批拒绝：风险过高"), done);
        // 工具始终未执行
        org.mockito.Mockito.verify(dlqRedeliverAccess, org.mockito.Mockito.never())
                .redeliver(any(), any());
    }

    @Test
    void HITL_重复审批409() throws Exception {
        stubModelForDlq();
        String runId = submit("MQ 死信积压了，帮我重投死信消息", "conv_hitl_3").replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        awaitStatus(runId, "WAITING_APPROVAL");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isOk());
        // 等 SUCCEEDED 后再审批 → 409
        awaitStatus(runId, "SUCCEEDED");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/runs/" + runId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isConflict());
    }
}
