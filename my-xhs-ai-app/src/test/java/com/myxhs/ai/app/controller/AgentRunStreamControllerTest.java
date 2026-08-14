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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 流式端点测试（mock 模型/工具，无真库）：
 * 事件流 RUN_STARTED→THINK→TOOL→THINK→ANSWER→COMPLETED 以 text/event-stream 推送。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sse;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=direct",
        "MCP_API_KEY=",
        "myxhs.ai.llm.api-key=test-key",
        "spring.ai-datasource.url=jdbc:h2:mem:aise;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa"
})
class AgentRunStreamControllerTest {

    private static final Pattern EV_PATTERN = Pattern.compile("证据 id=(ev_\\w+)");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatModel chatModel;

    @MockBean(name = "metricToolAccess")
    private MetricToolAccess metricToolAccess;

    @Test
    void runStream_推送完整事件流() throws Exception {
        when(metricToolAccess.queryOrderVolume("2026-08-01~2026-08-07")).thenReturn("volume=61");
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            List<ChatMessage> msgs = inv.getArgument(0, ChatRequest.class).messages();
            String all = msgs.stream().map(m -> m instanceof AiMessage a ? a.text()
                    : m instanceof UserMessage u ? u.singleText() : m.toString()).reduce("", String::concat);
            String ev = extractEv(all);
            String json;
            if (ev == null) {
                json = "{\"action\":\"TOOL_CALL\",\"tool\":\"queryOrderVolume\","
                        + "\"args\":{\"window\":\"2026-08-01~2026-08-07\"},\"reasoning\":\"查窗口\"}";
            } else {
                json = "{\"action\":\"ANSWER\",\"conclusion\":\"当前窗口下单量为61\","
                        + "\"evidenceRefs\":[\"" + ev + "\"],\"counterEvidence\":\"\",\"uncertainty\":\"\"}";
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(json))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(10, 5)).modelName("fake").build())
                    .build();
        });

        MvcResult result = mockMvc.perform(post("/api/ai/agent/run/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么订单量下降了\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("event:RUN_STARTED"), "应推送 RUN_STARTED: " + body);
        assertTrue(body.contains("event:TOOL"), "应推送 TOOL: " + body);
        assertTrue(body.contains("event:ANSWER"), "应推送 ANSWER: " + body);
        assertTrue(body.contains("event:COMPLETED"), "应推送 COMPLETED: " + body);
        assertTrue(body.contains("\"terminationReason\":\"COMPLETED\""), "终态应带终止原因: " + body);
        assertTrue(body.contains("下单量为61"), "终态应含最终答案: " + body);
    }

    private static String extractEv(String text) {
        Matcher m = EV_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
