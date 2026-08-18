package com.myxhs.ai.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DlqRedeliverTool 契约测试（2026-08-16 对接真实 Dashboard 后）：
 * fake HTTP server 验证 两段式请求（csrf 会话 → 重投）的 路径/参数/头 与真实契约一致。
 * 重投端点不实际触发（L3 动作；仅验证请求构造）。
 */
class DlqRedeliverToolTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final AtomicReference<String> csrfHeader = new AtomicReference<>();
    private final AtomicReference<String> cookieHeader = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rocketmq-dashboard/csrf-token", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            ex.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=fake-xsrf; Path=/");
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=fake-session; Path=/");
            respond(ex, 200, "{\"status\":0,\"data\":{\"token\":\"fake-csrf-token\"}}");
        });
        server.createContext("/message/consumeMessageDirectly.do", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            csrfHeader.set(ex.getRequestHeaders().getFirst("X-XSRF-TOKEN"));
            cookieHeader.set(ex.getRequestHeaders().getFirst("Cookie"));
            query.set(ex.getRequestURI().getQuery());
            respond(ex, 200, "{\"status\":0,\"data\":{\"recvTotal\":1,\"successCount\":1}}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void 真实契约_会话初始化后按msgId重投() {
        DlqRedeliverTool tool = new DlqRedeliverTool(base());
        String result = tool.redeliver("0123456789abcdef0123456789abcdef", "inventory-order-transaction-consumer-group");
        assertTrue(result.contains("\"status\":\"ok\""), result);
        assertTrue(result.contains("\"retryTopic\":\"%RETRY%inventory-order-transaction-consumer-group\""), result);
        // 两段请求顺序与路径（真实契约）
        assertEquals(2, requests.size());
        assertEquals("GET /rocketmq-dashboard/csrf-token", requests.get(0));
        assertEquals("POST /message/consumeMessageDirectly.do", requests.get(1));
        // 重投参数（msgId/consumerGroup/topic=推导 RETRY/clientId 空）
        String q = query.get();
        assertTrue(q.contains("msgId=0123456789abcdef0123456789abcdef"), q);
        assertTrue(q.contains("consumerGroup=inventory-order-transaction-consumer-group"), q);
        // getQuery() 返回解码后值（实际发送为 %25RETRY%25 编码形式）
        assertTrue(q.contains("topic=%RETRY%inventory-order-transaction-consumer-group"), q);
        assertTrue(q.endsWith("clientId="), q);
        // 头（CSRF token + session cookie）
        assertEquals("fake-csrf-token", csrfHeader.get());
        assertTrue(cookieHeader.get().contains("XSRF-TOKEN=fake-xsrf"), cookieHeader.get());
        assertTrue(cookieHeader.get().contains("SESSION=fake-session"), cookieHeader.get());
    }

    @Test
    void 重投失败_如实报错() {
        server.createContext("/message/fail.do", ex -> {
            respond(ex, 500, "server error");
        });
        // 覆盖：用独立 server 无法换 handler——直接构造错误场景（csrf 后 HTTP 500 由 fake 已覆盖不了，
        // 此处验证参数非法/未配置/会话失败路径）
        DlqRedeliverTool tool = new DlqRedeliverTool(base());
        String bad = tool.redeliver("bad-id", "g");
        assertTrue(bad.contains("msgId 必须为 32 位"), bad);
        DlqRedeliverTool noBase = new DlqRedeliverTool(null);
        assertTrue(noBase.redeliver("0123456789abcdef0123456789abcdef", "g").contains("未配置"));
    }

    @Test
    void csrf失败_如实报错() throws IOException {
        HttpServer srv2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv2.createContext("/rocketmq-dashboard/csrf-token", ex -> {
            respond(ex, 500, "oops");
        });
        srv2.start();
        try {
            DlqRedeliverTool tool = new DlqRedeliverTool("http://127.0.0.1:" + srv2.getAddress().getPort());
            String result = tool.redeliver("0123456789abcdef0123456789abcdef", "g");
            assertTrue(result.contains("会话初始化失败"), result);
            assertTrue(result.contains("\"status\":\"error\""), result);
        } finally {
            srv2.stop(0);
        }
    }

    @Test
    void 重投响应非零_如实报错() throws IOException {
        HttpServer srv2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv2.createContext("/rocketmq-dashboard/csrf-token", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=t; Path=/");
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=s; Path=/");
            respond(ex, 200, "{\"status\":0,\"data\":{\"token\":\"tok\"}}");
        });
        srv2.createContext("/message/consumeMessageDirectly.do", ex -> {
            respond(ex, 200, "{\"status\":-1,\"errMsg\":\"消息不存在\"}");
        });
        srv2.start();
        try {
            DlqRedeliverTool tool = new DlqRedeliverTool("http://127.0.0.1:" + srv2.getAddress().getPort());
            String result = tool.redeliver("0123456789abcdef0123456789abcdef", "g");
            assertTrue(result.contains("\"status\":\"error\""), result);
            assertTrue(result.contains("消息不存在"), result);
        } finally {
            srv2.stop(0);
        }
    }

    @Test
    void 查询DLQ消息_提取ORIGIN_MESSAGE_ID() {
        server.createContext("/message/queryMessageByTopic", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            csrfHeader.set(ex.getRequestHeaders().getFirst("X-XSRF-TOKEN"));
            query.set(ex.getRequestURI().getQuery());
            String body = "{\"status\":0,\"data\":{\"messages\":["
                    + "{\"msgId\":\"dlq001\",\"properties\":{\"ORIGIN_MESSAGE_ID\":\"aabbccdd11223344aabbccdd11223344\"},\"storeHost\":\"127.0.0.1:10911\",\"queueId\":\"0\",\"queueOffset\":\"10\"}"
                    + "]}}";
            respond(ex, 200, body);
        });
        DlqRedeliverTool tool = new DlqRedeliverTool(base());
        String result = tool.queryDlqMessages("inventory-order-transaction-consumer-group");
        assertTrue(result.contains("\"status\":\"ok\""), result);
        assertTrue(result.contains("\"originMsgId\":\"aabbccdd11223344aabbccdd11223344\""), result);
        assertTrue(result.contains("\"count\":1"), result);
        assertTrue(result.contains("\"dlqTopic\":\"%DLQ%inventory-order-transaction-consumer-group\""), result);
        // 验证请求路径和参数
        assertTrue(requests.stream().anyMatch(r -> r.contains("queryMessageByTopic")), requests.toString());
        assertTrue(query.get().contains("topic=%DLQ%inventory-order-transaction-consumer-group"), query.get());
        assertEquals("fake-csrf-token", csrfHeader.get());
    }

    @Test
    void 查询DLQ消息_无死信() {
        server.createContext("/message/queryMessageByTopic", ex -> {
            respond(ex, 200, "{\"status\":0,\"data\":{\"messages\":[]}}");
        });
        DlqRedeliverTool tool = new DlqRedeliverTool(base());
        String result = tool.queryDlqMessages("g");
        assertTrue(result.contains("\"status\":\"ok\""), result);
        assertTrue(result.contains("\"count\":0"), result);
        assertTrue(result.contains("无死信"), result);
    }

    @Test
    void 查询DLQ消息_参数非法() {
        DlqRedeliverTool tool = new DlqRedeliverTool(base());
        String bad = tool.queryDlqMessages("");
        assertTrue(bad.contains("\"status\":\"error\""), bad);
        DlqRedeliverTool noBase = new DlqRedeliverTool(null);
        assertTrue(noBase.queryDlqMessages("g").contains("未配置"), "未配置");
    }

    @Test
    void E2E闭环_查询DLQ后重投() throws IOException {
        HttpServer srv3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv3.createContext("/rocketmq-dashboard/csrf-token", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=t; Path=/");
            ex.getResponseHeaders().add("Set-Cookie", "SESSION=s; Path=/");
            respond(ex, 200, "{\"status\":0,\"data\":{\"token\":\"tok\"}}");
        });
        AtomicReference<String> redeliverQuery = new AtomicReference<>();
        srv3.createContext("/message/queryMessageByTopic", ex -> {
            String body = "{\"status\":0,\"data\":{\"messages\":["
                    + "{\"msgId\":\"dlq001\",\"properties\":{\"ORIGIN_MESSAGE_ID\":\"abcdef0123456789abcdef0123456789\"}}"
                    + "]}}";
            respond(ex, 200, body);
        });
        srv3.createContext("/message/consumeMessageDirectly.do", ex -> {
            redeliverQuery.set(ex.getRequestURI().getQuery());
            respond(ex, 200, "{\"status\":0,\"data\":{\"successCount\":1}}");
        });
        srv3.start();
        try {
            DlqRedeliverTool tool = new DlqRedeliverTool("http://127.0.0.1:" + srv3.getAddress().getPort());
            // Step 1: 查 DLQ
            String dlqResult = tool.queryDlqMessages("test-group");
            assertTrue(dlqResult.contains("\"status\":\"ok\""), dlqResult);
            assertTrue(dlqResult.contains("abcdef0123456789abcdef0123456789"), dlqResult);
            // Step 2: 用查询到的 ORIGIN_MESSAGE_ID 重投
            String redeliverResult = tool.redeliver("abcdef0123456789abcdef0123456789", "test-group");
            assertTrue(redeliverResult.contains("\"status\":\"ok\""), redeliverResult);
            // 验证重投请求里的 msgId 就是查询到的 ORIGIN_MESSAGE_ID
            assertTrue(redeliverQuery.get().contains("msgId=abcdef0123456789abcdef0123456789"), redeliverQuery.get());
        } finally {
            srv3.stop(0);
        }
    }
}
