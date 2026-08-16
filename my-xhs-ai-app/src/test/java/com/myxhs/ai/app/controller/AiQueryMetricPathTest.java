package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.agent.MetricAssistant;
import com.myxhs.ai.app.service.router.IntentRouter;
import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.DirectMetricToolAccess;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /api/ai/query metric 路径契约测试（H2，无模型）。
 * 工具源 = DirectMetricToolAccess（H2 直连），验证路由/确定性/error/traceId/agent 降级。
 */
class AiQueryMetricPathTest {

    private AiQueryController controller;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:qtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS t_order_0 (
                    id BIGINT PRIMARY KEY, user_id BIGINT, order_no VARCHAR(64),
                    status TINYINT, created_at DATETIME, deleted TINYINT DEFAULT 0
                )""");
        jdbc.update("DELETE FROM t_order_0");
        jdbc.update("INSERT INTO t_order_0 VALUES (1,100,'o1',1,'2026-08-01 00:00:00',0)");
        jdbc.update("INSERT INTO t_order_0 VALUES (2,100,'o2',4,'2026-08-03 12:00:00',0)");
        jdbc.update("INSERT INTO t_order_0 VALUES (3,100,'o3',3,'2026-08-05 12:00:00',0)");

        MetricToolAccess access = new DirectMetricToolAccess(
                new OrderMetricsTool(jdbc, "t_order_0"),
                new PaymentMetricsTool(jdbc),
                new ContentInteractionTool(jdbc));
        controller = new AiQueryController(
                new IntentRouter(),
                access,
                null, // agent 路径不测
                null); // 直答落库不测（仅 metric 路径）
    }

    @Test
    void 正常订单量走metric含确定性JSON() {
        Map<String, String> resp = controller.query(Map.of("message", "2026-08-01 到 2026-08-07 的下单量"), null);
        assertEquals("metric", resp.get("path"));
        assertEquals("METRIC_ORDER_VOLUME", resp.get("intent"));
        assertTrue(resp.get("result").contains("\"value\":3"), "应为确定性3: " + resp);
        assertTrue(resp.containsKey("traceId") && !resp.get("traceId").isBlank(),
                "响应应含 traceId（可溯源）: " + resp);
    }

    @Test
    void 超31天窗口返回error仍带traceId() {
        Map<String, String> resp = controller.query(Map.of("message", "2026-07-01 到 2026-09-01 的订单量"), null);
        assertEquals("error", resp.get("status"));
        assertTrue(resp.containsKey("traceId") && !resp.get("traceId").isBlank(),
                "error 响应也应含 traceId（可溯源定位）: " + resp);
    }

    @Test
    void from大于to返回error不抛() {
        Map<String, String> resp = controller.query(Map.of("message", "2026-08-07 到 2026-08-01 的订单量"), null);
        assertEquals("error", resp.get("status"));
    }

    @Test
    void agent路径模型不可用返回error不抛() {
        // assistant 为 null（构造传 null）= 模拟模型不可用 → 应返回 error JSON，不 500
        Map<String, String> resp = controller.query(Map.of("message", "为什么互动下降了？"), null);
        assertEquals("agent", resp.get("path"));
        assertEquals("error", resp.get("status"));
        assertTrue(resp.get("error").contains("模型暂不可用"), "应含降级文案: " + resp);
        assertTrue(resp.containsKey("traceId") && !resp.get("traceId").isBlank());
    }
}
