package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件流水分析工具（D4 A 面：A1 漏斗 / A2 支付失败 / A3 内容发布）。
 * 数据源 = 四张新事件表（append-only，2026-08-14 由业务侧补齐）+ 复用订单/支付口径：
 *  - my_xhs_product.t_product_behavior（商品浏览，behavior_type=1）
 *  - my_xhs_cart.t_cart_event（加购事件，action=ADD）
 *  - my_xhs_payment.t_payment_event（支付事件，PAY_FAIL/error_code）
 *  - my_xhs_content.t_note_event（笔记事件，PUBLISH）
 * 窗口语义与订单工具一致：yyyy-MM-dd~yyyy-MM-dd，半开 [start 00:00, end+1 00:00)。
 */
public class EventAnalyticsTool {

    private static final Logger log = LoggerFactory.getLogger(EventAnalyticsTool.class);

    public static final String METRIC_FUNNEL = "funnel.conversion";
    public static final String METRIC_PAY_FAILURES = "payment.failures";
    public static final String METRIC_NOTE_PUBLISH = "content.publish_events";
    public static final int MAX_WINDOW_DAYS = 31;

    private final JdbcTemplate jdbc;
    private final OrderMetricsTool orderTool;
    private final PaymentMetricsTool paymentTool;
    private final ObjectMapper om;

    public EventAnalyticsTool(JdbcTemplate jdbc, OrderMetricsTool orderTool, PaymentMetricsTool paymentTool) {
        this(jdbc, orderTool, paymentTool, new ObjectMapper());
    }

    public EventAnalyticsTool(JdbcTemplate jdbc, OrderMetricsTool orderTool, PaymentMetricsTool paymentTool,
                              ObjectMapper om) {
        this.jdbc = jdbc;
        this.orderTool = orderTool;
        this.paymentTool = paymentTool;
        this.om = om;
    }

    /** A1：电商漏斗四环（浏览→加购→下单→支付）窗口内计数 */
    @Tool("查询电商漏斗各环节量（商品浏览/加购/下单/支付，窗口内；口径：浏览=详情页事件、加购=ADD 事件、下单=排除已删、支付=成功 status=1）")
    public String funnelConversion(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，跨度不超过31天") String window) {
        LocalDateTime[] bounds = MetricTimeWindow.parse(window, MAX_WINDOW_DAYS);
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_FUNNEL);
        node.put("window", window);
        node.put("asOf", java.time.Instant.now().toString());
        try {
            Long browse = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM my_xhs_product.t_product_behavior"
                            + " WHERE behavior_type = 1 AND event_time >= ? AND event_time < ?",
                    Long.class, bounds[0], bounds[1]);
            Long cartAdd = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM my_xhs_cart.t_cart_event"
                            + " WHERE action = 'ADD' AND event_time >= ? AND event_time < ?",
                    Long.class, bounds[0], bounds[1]);
            long order = parseLong(orderTool.queryOrderVolume(window), "value");
            long paySuccess = parseLong(paymentTool.paymentSuccessRate(window), "success");
            long b = browse == null ? 0 : browse;
            long c = cartAdd == null ? 0 : cartAdd;
            node.put("browse", b);
            node.put("cartAdd", c);
            node.put("order", order);
            node.put("paySuccess", paySuccess);
            node.put("browseToCartRate", round(b == 0 ? 0 : c * 100.0 / b));
            node.put("cartToOrderRate", round(c == 0 ? 0 : order * 100.0 / c));
            node.put("note", "事件表为空时各环为 0；漏斗环节为独立计数（非同一用户路径）；支付环=成功 status=1");
        } catch (Exception e) {
            return error(node, "事件表查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** A2：支付失败事件（按失败码聚合，窗口内） */
    @Tool("查询支付失败事件（t_payment_event PAY_FAIL 按失败码聚合；含失败总数与按码分布）")
    public String paymentFailures(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，跨度不超过31天") String window) {
        LocalDateTime[] bounds = MetricTimeWindow.parse(window, MAX_WINDOW_DAYS);
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_PAY_FAILURES);
        node.put("window", window);
        node.put("asOf", java.time.Instant.now().toString());
        try {
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM my_xhs_payment.t_payment_event"
                            + " WHERE event_type = 'PAY_FAIL' AND event_time >= ? AND event_time < ?",
                    Long.class, bounds[0], bounds[1]);
            node.put("totalFailures", total == null ? 0 : total);
            ArrayNode byCode = node.putArray("byErrorCode");
            jdbc.query(
                    "SELECT COALESCE(error_code,'UNKNOWN') AS code, COUNT(*) AS cnt"
                            + " FROM my_xhs_payment.t_payment_event"
                            + " WHERE event_type = 'PAY_FAIL' AND event_time >= ? AND event_time < ?"
                            + " GROUP BY error_code ORDER BY cnt DESC",
                    rs -> {
                        byCode.addObject().put("errorCode", rs.getString("code")).put("count", rs.getLong("cnt"));
                    }, bounds[0], bounds[1]);
            node.put("note", "失败码为模拟渠道语义（CHANNEL_REJECT 等）；事件表为空=0");
        } catch (Exception e) {
            return error(node, "支付事件表查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** A3：内容发布事件（按天，窗口内） */
    @Tool("查询内容发布事件数（t_note_event PUBLISH 按天聚合；口径：发布即审核通过=audited_at 同刻）")
    public String notePublishEvents(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，跨度不超过31天") String window) {
        LocalDateTime[] bounds = MetricTimeWindow.parse(window, MAX_WINDOW_DAYS);
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_NOTE_PUBLISH);
        node.put("window", window);
        node.put("asOf", java.time.Instant.now().toString());
        try {
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM my_xhs_content.t_note_event"
                            + " WHERE event_type = 'PUBLISH' AND event_time >= ? AND event_time < ?",
                    Long.class, bounds[0], bounds[1]);
            node.put("totalPublishes", total == null ? 0 : total);
            ArrayNode byDay = node.putArray("byDay");
            jdbc.query(
                    "SELECT DATE(event_time) AS d, COUNT(*) AS cnt"
                            + " FROM my_xhs_content.t_note_event"
                            + " WHERE event_type = 'PUBLISH' AND event_time >= ? AND event_time < ?"
                            + " GROUP BY DATE(event_time) ORDER BY d",
                    rs -> {
                        byDay.addObject().put("day", String.valueOf(rs.getDate("d"))).put("count", rs.getLong("cnt"));
                    }, bounds[0], bounds[1]);
            node.put("note", "发布=审核通过同刻（无独立审核链路）；事件表为空=0");
        } catch (Exception e) {
            return error(node, "笔记事件表查询失败: " + e.getMessage());
        }
        return write(node);
    }

    private long parseLong(String json, String field) {
        try {
            return om.readTree(json).path(field).asLong();
        } catch (Exception e) {
            return -1;
        }
    }

    private String error(ObjectNode node, String msg) {
        node.put("status", "error");
        node.put("error", msg);
        return write(node);
    }

    private String write(ObjectNode node) {
        try {
            return om.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\"序列化失败\"}";
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
