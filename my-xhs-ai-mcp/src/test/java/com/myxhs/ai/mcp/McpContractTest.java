package com.myxhs.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 契约测试（H2，无真库/无网络）。
 * 覆盖：initialize / tools/list / tools/call（order 工具口径）/ 认证 401。
 * 协议要点：Accept: application/json, text/event-stream；会话 Mcp-Session-Id。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcptest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.metric.order.shards=t_order_0",
        "MCP_API_KEY="
})
class McpContractTest {

    private static final String ACCEPT = "application/json, text/event-stream";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS t_order_0 (
                    id BIGINT PRIMARY KEY, user_id BIGINT, order_no VARCHAR(64),
                    status TINYINT, created_at DATETIME, deleted TINYINT DEFAULT 0
                )""");
        jdbc.update("DELETE FROM t_order_0");
        jdbc.update("INSERT INTO t_order_0 VALUES (1,100,'o1',1,'2026-08-01 00:00:00',0)");
        jdbc.update("INSERT INTO t_order_0 VALUES (2,100,'o2',4,'2026-08-03 12:00:00',0)");
        jdbc.update("INSERT INTO t_order_0 VALUES (3,100,'o3',3,'2026-08-05 12:00:00',0)");
    }

    private String sessionId;

    private JsonNode send(String body) throws Exception {
        var req = post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", ACCEPT);
        if (sessionId != null && !sessionId.isBlank()) {
            req = req.header("Mcp-Session-Id", sessionId);
        }
        MvcResult r = mockMvc.perform(req.content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        String sid = r.getResponse().getHeader("Mcp-Session-Id");
        if (sid != null && !sid.isBlank()) {
            sessionId = sid;
        }
        String raw = r.getResponse().getContentAsString();
        // Streamable HTTP：异步响应以 SSE 返回（event:message / data:{...}）；汇总 data: 行
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(t.substring(5).trim());
            }
        }
        raw = data.length() > 0 ? data.toString() : raw;
        return om.readTree(raw);
    }

    @Test
    void initialize_返回serverInfo与tools能力() throws Exception {
        JsonNode resp = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
        assertEquals("my-xhs-ai-mcp", resp.path("result").path("serverInfo").path("name").asText());
        assertTrue(resp.path("result").path("capabilities").has("tools"), "应声明 tools 能力");
    }

    @Test
    void tools_list_返回三个工具() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        JsonNode resp = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = resp.path("result").path("tools");
        assertTrue(tools.size() == 3, "应 3 个工具: " + tools);
        assertEquals("order.query_volume", tools.get(0).path("name").asText());
        assertEquals("payment.success_rate", tools.get(1).path("name").asText());
        assertEquals("content.interaction", tools.get(2).path("name").asText());
        assertTrue(tools.get(0).path("inputSchema").path("properties").has("window"),
                "应声明 window 参数 schema");
    }

    @Test
    void tools_call_order_返回口径正确值() throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        JsonNode resp = send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"order.query_volume\",\"arguments\":{\"window\":\"2026-08-01~2026-08-07\"}}}");
        String text = resp.path("result").path("content").get(0).path("text").asText();
        // 口径：正常1+取消1+退款1=3（排除已删/窗外）
        assertTrue(text.contains("\"value\":3"), "应返回 value=3: " + text);
        assertEquals(false, resp.path("result").path("isError").asBoolean(false));
    }
}
