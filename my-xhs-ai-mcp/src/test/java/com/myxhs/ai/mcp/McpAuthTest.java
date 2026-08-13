package com.myxhs.ai.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MCP 认证测试（真实 HTTP，RANDOM_PORT）：设置 MCP_API_KEY 后，
 * 未带/带错 token → 401；带对 token → 200（Gate「非授权调用 100% 拒绝」）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "myxhs.ai.metric.order.shards=t_order_0",
        "MCP_API_KEY=test-secret-key-123"
})
class McpAuthTest {

    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    private static final String ACCEPT = "application/json, text/event-stream";

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> call(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.CONTENT_TYPE, "application/json");
        h.set("Accept", ACCEPT);
        if (token != null) {
            h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return rest.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(BODY, h), String.class);
    }

    @Test
    void 未带token_401() {
        assertEquals(HttpStatus.UNAUTHORIZED, call(null).getStatusCode());
    }

    @Test
    void 错token_401() {
        assertEquals(HttpStatus.UNAUTHORIZED, call("wrong-key").getStatusCode());
    }

    @Test
    void 对token_200() {
        assertEquals(HttpStatus.OK, call("test-secret-key-123").getStatusCode());
    }
}
