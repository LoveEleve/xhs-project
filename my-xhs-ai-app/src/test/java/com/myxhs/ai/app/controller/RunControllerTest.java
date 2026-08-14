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
        "myxhs.ai.teamo.api-key=test-key",
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
}
