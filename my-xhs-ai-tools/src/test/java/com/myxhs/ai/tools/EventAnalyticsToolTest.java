package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EventAnalyticsTool 单测（H2 五表：漏斗/支付事件/笔记事件/订单/支付）。
 */
class EventAnalyticsToolTest {

    private final ObjectMapper om = new ObjectMapper();
    private JdbcTemplate jdbc;
    private EventAnalyticsTool tool;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:evt;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        // 与生产一致的库前缀（工具硬编码 my_xhs_*，H2 建同名 schema）
        for (String schema : new String[]{"my_xhs_product", "my_xhs_cart", "my_xhs_payment", "my_xhs_content"}) {
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS my_xhs_product.t_product_behavior (id BIGINT PRIMARY KEY, user_id BIGINT,"
                + " spu_id BIGINT, sku_id BIGINT, behavior_type TINYINT, event_time DATETIME)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS my_xhs_cart.t_cart_event (id BIGINT PRIMARY KEY, user_id BIGINT, sku_id BIGINT,"
                + " action VARCHAR(16), quantity INT, checked TINYINT, event_time DATETIME, msg_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS my_xhs_payment.t_payment_event (id BIGINT PRIMARY KEY, payment_no VARCHAR(64),"
                + " order_id BIGINT, user_id BIGINT, event_type VARCHAR(32), error_code VARCHAR(16),"
                + " error_msg VARCHAR(255), event_time DATETIME)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS my_xhs_content.t_note_event (id BIGINT PRIMARY KEY, note_id BIGINT, user_id BIGINT,"
                + " event_type VARCHAR(16), status TINYINT, audit_status TINYINT, event_time DATETIME)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_order_0 (id BIGINT PRIMARY KEY, user_id BIGINT, order_no VARCHAR(64),"
                + " status TINYINT, created_at DATETIME, deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS my_xhs_payment.t_payment (id BIGINT PRIMARY KEY, order_id BIGINT, payment_no VARCHAR(64),"
                + " status TINYINT, pay_type TINYINT, created_at DATETIME, deleted TINYINT DEFAULT 0)");
        jdbc.update("DELETE FROM my_xhs_product.t_product_behavior; DELETE FROM my_xhs_cart.t_cart_event;"
                + " DELETE FROM my_xhs_payment.t_payment_event;"
                + " DELETE FROM my_xhs_content.t_note_event; DELETE FROM t_order_0; DELETE FROM my_xhs_payment.t_payment;");
        // 窗口 08-01~08-07 内：浏览 10、加购 5、下单 3（2 正常+1 退款）、支付成功 2
        jdbc.update("INSERT INTO my_xhs_product.t_product_behavior VALUES (1,100,1,1,1,'2026-08-02 10:00:00')");
        jdbc.update("INSERT INTO my_xhs_product.t_product_behavior VALUES (2,100,1,1,1,'2026-08-03 10:00:00')");
        jdbc.update("INSERT INTO my_xhs_cart.t_cart_event VALUES (1,100,1,'ADD',1,1,'2026-08-03 11:00:00','m1')");
        jdbc.update("INSERT INTO my_xhs_cart.t_cart_event VALUES (2,101,2,'ADD',2,1,'2026-08-04 11:00:00','m2')");
        jdbc.update("INSERT INTO t_order_0 VALUES (1,100,'o1',1,'2026-08-03 12:00:00',0)");
        jdbc.update("INSERT INTO t_order_0 VALUES (2,100,'o2',3,'2026-08-05 12:00:00',0)");
        jdbc.update("INSERT INTO t_order_0 VALUES (3,101,'o3',1,'2026-08-06 12:00:00',0)");
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (1,1,'p1',1,1,'2026-08-03 12:30:00',0)");
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (2,2,'p2',2,1,'2026-08-05 12:30:00',0)");
        jdbc.update("INSERT INTO my_xhs_payment.t_payment_event VALUES (1,'p2',2,100,'PAY_FAIL','CHANNEL_REJECT','渠道拒绝','2026-08-05 12:30:00')");
        jdbc.update("INSERT INTO my_xhs_payment.t_payment_event VALUES (2,'p3',3,101,'PAY_FAIL','CHANNEL_REJECT','渠道拒绝','2026-08-06 12:30:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_note_event VALUES (1,1,100,'PUBLISH',2,1,'2026-08-03 09:00:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_note_event VALUES (2,2,101,'PUBLISH',2,1,'2026-08-04 09:00:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_note_event VALUES (3,3,102,'PUBLISH',2,1,'2026-08-05 09:00:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_note_event VALUES (4,4,100,'PUBLISH',2,1,'2026-08-06 09:00:00')");
        tool = new EventAnalyticsTool(jdbc,
                new OrderMetricsTool(jdbc, "t_order_0"),
                new PaymentMetricsTool(jdbc), om);
    }

    @Test
    void 漏斗四环_计数正确() throws Exception {
        JsonNode r = om.readTree(tool.funnelConversion("2026-08-01~2026-08-07"));
        assertEquals("ok", r.path("status").asText(), "error=" + r.path("error").asText());
        assertEquals(2, r.path("browse").asInt());
        assertEquals(2, r.path("cartAdd").asInt());
        assertEquals(3, r.path("order").asInt());
        assertEquals(1, r.path("paySuccess").asInt()); // status=1 仅 1 笔
        assertEquals(100, r.path("browseToCartRate").asInt());
        assertEquals(150, r.path("cartToOrderRate").asInt());
    }

    @Test
    void 支付失败_按失败码聚合() throws Exception {
        JsonNode r = om.readTree(tool.paymentFailures("2026-08-01~2026-08-07"));
        assertEquals("ok", r.path("status").asText(), "error=" + r.path("error").asText());
        assertEquals(2, r.path("totalFailures").asInt());
        assertEquals("CHANNEL_REJECT", r.path("byErrorCode").get(0).path("errorCode").asText());
        assertEquals(2, r.path("byErrorCode").get(0).path("count").asInt());
    }

    @Test
    void 内容发布_按天聚合() throws Exception {
        JsonNode r = om.readTree(tool.notePublishEvents("2026-08-01~2026-08-07"));
        assertEquals("ok", r.path("status").asText(), "error=" + r.path("error").asText());
        assertEquals(4, r.path("totalPublishes").asInt());
        assertEquals(4, r.path("byDay").size());
    }

    @Test
    void 窗口外数据不计入() throws Exception {
        JsonNode r = om.readTree(tool.funnelConversion("2026-08-10~2026-08-16"));
        assertEquals(0, r.path("browse").asInt());
        assertEquals(0, r.path("cartAdd").asInt());
        assertEquals(0, r.path("order").asInt());
    }
}
