package com.myxhs.ai.tools;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PrometheusQueryTool 测试（内嵌 HTTP server 模拟 PromQL API，无真实 Prometheus）。
 * 覆盖：5xx 聚合、慢端点 P95 top、参数校验、错误响应。
 */
class PrometheusQueryToolTest {

    private static HttpServer server;
    private static String baseUrl;
    private static volatile String lastQuery;
    /** 测试覆盖：下一个查询响应（null=默认分支） */
    private static volatile String overrideBody;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            String q = new String(java.net.URLDecoder.decode(
                    exchange.getRequestURI().getRawQuery().replaceFirst("^query=", ""), StandardCharsets.UTF_8));
            lastQuery = q;
            String body;
            if (overrideBody != null) {
                body = overrideBody;
                overrideBody = null;
            } else if (q.contains("status=~\"5..\"")) {
                // 模拟 Prometheus 语义：带 service 过滤时只返回该服务（order 12+3=15），否则全部（+gateway 1=16）
                boolean orderOnly = q.contains("service=\"my-xhs-order\"");
                boolean gatewayOnly = q.contains("service=\"my-xhs-gateway\"");
                if (gatewayOnly) {
                    // gateway 噪音场景：只有 uri=/** 的 5xx（扫描探测）
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                            + "{\"metric\":{\"uri\":\"/**\",\"status\":\"500\",\"service\":\"my-xhs-gateway\"},\"value\":[1,\"9\"]}"
                            + "]}}";
                } else {
                    body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                            + "{\"metric\":{\"uri\":\"/api/orders\",\"status\":\"500\",\"service\":\"my-xhs-order\"},\"value\":[1,\"12\"]},"
                            + "{\"metric\":{\"uri\":\"/api/orders\",\"status\":\"503\",\"service\":\"my-xhs-order\"},\"value\":[1,\"3\"]}"
                            + (orderOnly ? "" : ",{\"metric\":{\"uri\":\"/health\",\"status\":\"500\",\"service\":\"my-xhs-gateway\"},\"value\":[1,\"1\"]}")
                            + "]}}";
                }
            } else if (q.contains("rocketmq_consumer_lag")) {
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"group\":\"cart-sync-consumer-group\"},\"value\":[1,\"120\"]},"
                        + "{\"metric\":{\"group\":\"order-event-consumer-group\"},\"value\":[1,\"5\"]}"
                        + "]}}";
            } else if (q.contains("rocketmq_dlq_backlog")) {
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"consumer_group\":\"order-event-consumer-group\"},\"value\":[1,\"42\"]}"
                        + "]}}";
            } else if (q.contains("mysql_slave_status_seconds_behind_master")) {
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"instance\":\"21.130.247.89:9105\",\"master_host\":\"127.0.0.1\"},\"value\":[1,\"0\"]}"
                        + "]}}";
            } else if (q.contains("mysql_innodb_deadlock_total")) {
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"instance\":\"21.130.247.89:9100\"},\"value\":[1,\"2\"]}"
                        + "]}}";
            } else if (q.contains("mysql_innodb_deadlock_new_events")) {
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"instance\":\"21.130.247.89:9100\"},\"value\":[1,\"0\"]}"
                        + "]}}";
            } else if (q.contains("histogram_quantile")) {
                body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"uri\":\"/api/orders\",\"service\":\"my-xhs-order\"},\"value\":[1,\"0.85\"]},"
                        + "{\"metric\":{\"uri\":\"/api/slow\",\"service\":\"my-xhs-order\"},\"value\":[1,\"2.5\"]}"
                        + "]}}";
            } else {
                body = "{\"status\":\"error\",\"error\":\"unexpected\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private final ObjectMapper om = new ObjectMapper();
    private final PrometheusQueryTool tool = new PrometheusQueryTool(baseUrl, om);

    @Test
    void 查询5xx_按uri聚合总错误数() throws Exception {
        var r = om.readTree(tool.httpErrors("my-xhs-order", "6"));
        assertEquals("ok", r.path("status").asText());
        assertEquals(15, r.path("total5xx").asInt());
        assertEquals(0, r.path("noiseScanRoutes").asInt());
        assertEquals(1, r.path("byUri").size()); // 500+503 同 uri 合并
        assertEquals("/api/orders", r.path("byUri").get(0).path("uri").asText());
        assertEquals(15, r.path("byUri").get(0).path("rate5xxPerSec").asInt());
        assertTrue(lastQuery.contains("increase("), "应使用 increase 窗口查询: " + lastQuery);
        assertTrue(lastQuery.contains("status=~\"5..\""), "应过滤 5xx: " + lastQuery);
        assertTrue(lastQuery.contains("service=\"my-xhs-order\""), "应过滤服务: " + lastQuery);
    }

    @Test
    void 查询5xx_UNKNOWN路由单列为噪音() throws Exception {
        // mock 返回 uri=/** 的 5xx（gateway 扫描噪音），应进 noiseScanRoutes 而非 byUri
        var r = om.readTree(tool.httpErrors("my-xhs-gateway", "24"));
        assertEquals("ok", r.path("status").asText());
        assertEquals(9, r.path("noiseScanRoutes").asInt());
        assertEquals(0, r.path("byUri").size());
        assertEquals(9, r.path("total5xx").asInt());
    }

    @Test
    void 查询慢端点_P95排序() throws Exception {
        var r = om.readTree(tool.httpLatency("my-xhs-order", "24"));
        assertEquals("ok", r.path("status").asText());
        assertEquals("/api/slow", r.path("topLatency").get(0).path("uri").asText());
        assertEquals(2.5, r.path("topLatency").get(0).path("p95Seconds").asDouble());
        assertTrue(lastQuery.contains("histogram_quantile(0.95"), "应使用 P95: " + lastQuery);
    }

    @Test
    void 参数校验_hours非法返回error() throws Exception {
        assertEquals("error", om.readTree(tool.httpErrors("my-xhs-order", "0")).path("status").asText());
        assertEquals("error", om.readTree(tool.httpErrors("my-xhs-order", "169")).path("status").asText());
        assertEquals("error", om.readTree(tool.httpErrors("my-xhs-order", "abc")).path("status").asText());
    }

    @Test
    void 查询消费积压_按组聚合排序() throws Exception {
        var r = om.readTree(tool.mqConsumerLag(""));
        assertEquals("ok", r.path("status").asText());
        assertEquals(125, r.path("totalLag").asInt());
        assertEquals(2, r.path("groupCount").asInt());
        assertEquals("cart-sync-consumer-group", r.path("topGroups").get(0).path("group").asText());
        assertEquals(120, r.path("topGroups").get(0).path("lag").asInt());
        assertTrue(lastQuery.contains("rocketmq_consumer_lag"), lastQuery);
    }

    @Test
    void 查询死信积压() throws Exception {
        var r = om.readTree(tool.mqDlqBacklog("order-event-consumer-group"));
        assertEquals("ok", r.path("status").asText());
        assertEquals(42, r.path("totalDlqBacklog").asInt());
        assertTrue(lastQuery.contains("consumer_group=\"order-event-consumer-group\""), lastQuery);
    }

    @Test
    void 死信积压_哨兵负值排除聚合() throws Exception {
        // 中间件口径核实（2026-08-16）：-1 = 无 DLQ/查询失败哨兵，求和无意义——
        // 混合 -1 与正值时，聚合只计 >0 组，哨兵组数单独输出
        overrideBody = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"consumer_group\":\"g-a\"},\"value\":[1,\"-1\"]},"
                + "{\"metric\":{\"consumer_group\":\"g-b\"},\"value\":[1,\"5\"]},"
                + "{\"metric\":{\"consumer_group\":\"g-c\"},\"value\":[1,\"-1\"]}"
                + "]}}";
        var r = om.readTree(tool.mqDlqBacklog(""));
        assertEquals("ok", r.path("status").asText());
        assertEquals(5, r.path("totalDlqBacklog").asInt(), "只计 >0 组（-283 类哨兵求和不再出现）");
        assertEquals(2, r.path("sentinelGroups").asInt(), "哨兵组数单列");
        assertEquals(1, r.path("groupCount").asInt());
        assertEquals("g-b", r.path("topGroups").get(0).path("group").asText());
        assertTrue(r.path("note").asText().contains("哨兵"), r.path("note").asText());
    }

    @Test
    void 消费积压_哨兵负值排除聚合() throws Exception {
        // 同款口径（2026-08-16）：consumer_lag 的 -1 = mqadmin 哨兵（无在线消费者/无 offset），
        // 排除聚合；0 = 真实无积压，保留
        overrideBody = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"group\":\"g-a\"},\"value\":[1,\"-1\"]},"
                + "{\"metric\":{\"group\":\"g-b\"},\"value\":[1,\"120\"]},"
                + "{\"metric\":{\"group\":\"g-c\"},\"value\":[1,\"0\"]}"
                + "]}}";
        var r = om.readTree(tool.mqConsumerLag(""));
        assertEquals("ok", r.path("status").asText());
        assertEquals(120, r.path("totalLag").asInt(), "排除 -1 哨兵，0 保留");
        assertEquals(1, r.path("sentinelGroups").asInt());
        assertEquals(2, r.path("groupCount").asInt(), "g-b + g-c");
    }

    @Test
    void 组名注入防护_非法字符拒绝() throws Exception {
        var r = om.readTree(tool.mqConsumerLag("a\";drop"));
        assertEquals("error", r.path("status").asText());
    }

    @Test
    void 查询复制延迟() throws Exception {
        var r = om.readTree(tool.mysqlReplicationLag());
        assertEquals("ok", r.path("status").asText());
        assertEquals(1, r.path("replicaCount").asInt());
        assertEquals(0, r.path("replicas").get(0).path("secondsBehindMaster").asInt());
        assertTrue(lastQuery.contains("mysql_slave_status_seconds_behind_master"), lastQuery);
    }

    @Test
    void 查询死锁事件() throws Exception {
        var r = om.readTree(tool.mysqlDeadlocks());
        assertEquals("ok", r.path("status").asText());
        assertEquals(2, r.path("deadlockTotal").asInt());
        assertEquals(0, r.path("deadlockNewEvents").asInt());
    }

    @Test
    void service为空_查询全部服务() throws Exception {
        var r = om.readTree(tool.httpErrors("", "6"));
        assertEquals("ok", r.path("status").asText());
        assertEquals(16, r.path("total5xx").asInt());
        assertTrue(!lastQuery.contains("service=\""), "空 service 不应嵌入过滤: " + lastQuery);
    }
}
