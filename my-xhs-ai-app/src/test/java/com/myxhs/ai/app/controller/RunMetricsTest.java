package com.myxhs.ai.app.controller;

import com.myxhs.ai.tools.MetricToolAccess;
import dev.langchain4j.data.message.AiMessage;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-3 可观测测试：/actuator/prometheus 暴露 run 级指标；run 完成后计数/token/成本更新。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metrics;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.tools.mode=direct",
        "MCP_API_KEY=",
        "myxhs.ai.llm.api-key=test-key",
        "spring.ai-datasource.url=jdbc:h2:mem:metricsai;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.ai-datasource.username=sa",
        "spring.ai-datasource.password=sa",
        "management.endpoints.web.exposure.include=prometheus,health"
})
class RunMetricsTest {

    private static final Pattern EV = Pattern.compile("证据 id=(ev_\\w+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private ChatModel chatModel;

    @MockBean(name = "metricToolAccess")
    private MetricToolAccess metricToolAccess;

    @Test
    void prometheus端点_暴露run指标() throws Exception {
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

        // 提交并等待完成
        String sub = mockMvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"为什么订单量下降了\"}"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String runId = sub.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        for (int i = 0; i < 50; i++) {
            String s = mockMvc.perform(get("/api/runs/" + runId))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (s.contains("SUCCEEDED")) {
                break;
            }
            Thread.sleep(100);
        }

        // 健康端点可达
        String health = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(health.contains("UP"), "health 应 UP: " + health);

        // 指标已在 MeterRegistry 注册（HTTP 暴露由本地 jar + M8 部署验证）
        assertTrue(meterRegistry.get("myxhs_ai_runs_total").counter() != null, "应注册 run 计数");
        assertTrue(meterRegistry.get("myxhs_ai_runs_total").tag("status", "SUCCEEDED").counter() != null,
                "应注册 SUCCEEDED 计数");
        assertTrue(meterRegistry.get("myxhs_ai_tokens_total").counter() != null, "应注册 token 累计");
        assertTrue(meterRegistry.get("myxhs_ai_cost_total").counter() != null, "应注册成本累计");
        assertTrue(meterRegistry.get("myxhs_ai_run_duration").timer() != null, "应注册耗时 timer");
        assertTrue(meterRegistry.get("myxhs_ai_runs_running").gauge() != null, "应注册运行中 gauge");
        // run 完成后计数有值
        assertTrue(meterRegistry.get("myxhs_ai_tokens_total").counter().count() > 0, "token 应累计");
    }
}
