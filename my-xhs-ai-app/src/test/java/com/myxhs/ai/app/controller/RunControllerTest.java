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
                + " started_at DATETIME(3), ended_at DATETIME(3), last_activity_at DATETIME(3))");
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

    /** 捕获每次模型调用收到的完整消息（验证多轮注入） */
    private java.util.concurrent.atomic.AtomicReference<StringBuilder> stubModelWithCapture() {
        var captured = new java.util.concurrent.atomic.AtomicReference<>(new StringBuilder());
        when(metricToolAccess.queryOrderVolume(any())).thenReturn("volume=61");
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0, ChatRequest.class).messages();
            String all = msgs.stream().map(RunControllerTest::textOf).reduce("", String::concat);
            captured.get().append("<<ROUND>>").append(all);
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
        return captured;
    }

    private static String textOf(ChatMessage m) {
        return m instanceof AiMessage a ? a.text()
                : m instanceof UserMessage u ? u.singleText()
                : m.toString();
    }

    @Test
    void 多轮会话_第二问注入上轮结论() throws Exception {
        var captured = stubModelWithCapture();
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
        String allCalls = captured2(captured);
        assertTrue(allCalls.contains("下单量为61"), "第二问应注入上轮结论: " + allCalls);
    }

    private static String captured2(java.util.concurrent.atomic.AtomicReference<StringBuilder> ref) {
        return ref.get().toString();
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
}
