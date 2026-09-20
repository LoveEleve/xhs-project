package com.myxhs.ai.business;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 业务只读查询（订单/支付/退款/库存/券/通知）。
 * <p>AI 服务对业务库仅有 SELECT 权限；订单分片路由与订单服务一致：h=userId.hashCode()&amp;0x7fffffff，db=h%4，table=(h/4)%4。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessQueryService {

    private static final Map<Integer, String> ORDER_STATUS = Map.of(
            0, "待付款", 1, "已付款", 2, "已发货", 3, "已完成", 4, "已取消", 5, "已退款");
    private static final Map<Integer, String> PAY_STATUS = Map.of(
            0, "待支付", 1, "支付成功", 2, "支付失败", 3, "已退款");
    private static final Map<Integer, String> REFUND_STATUS = Map.of(
            0, "退款中", 1, "退款成功", 2, "退款失败", 3, "退款关闭");

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> orderTrace(long userId, String orderNo) {
        int h = Long.hashCode(userId) & 0x7fffffff; // 与订单服务分片表达式 user_id.hashCode() 一致
        int db = h % 4;
        int table = (h / 4) % 4;
        String orderTable = "my_xhs_order_" + db + ".t_order_" + table;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, order_no, status, total_amount, pay_amount, discount_amount, coupon_id,"
                        + " created_at, paid_at, delivered_at, completed_at, cancelled_at"
                        + " FROM " + orderTable + " WHERE user_id = ? AND order_no = ?", userId, orderNo);
        if (rows.isEmpty()) {
            Map<String, Object> miss = new LinkedHashMap<>();
            miss.put("found", false);
            miss.put("shard", "db" + db + ".t_order_" + table);
            miss.put("hint", "该用户分片内未找到此订单号；请核对 userId 与 orderNo 是否匹配");
            return miss;
        }
        Map<String, Object> order = new LinkedHashMap<>(rows.get(0));
        long orderId = ((Number) order.get("id")).longValue();
        order.put("statusText", text(order.get("status"), ORDER_STATUS));

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT sku_id, sku_name, price, quantity, total_amount FROM my_xhs_order_" + db + ".t_order_item_" + table
                        + " WHERE order_id = ? AND user_id = ?", orderId, userId);
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_seq, event_type, from_status, to_status, event_time FROM my_xhs_order_" + db + ".t_order_event_" + table
                        + " WHERE order_id = ? ORDER BY event_seq", orderId);
        for (Map<String, Object> e : events) {
            e.put("fromText", text(e.get("from_status"), ORDER_STATUS));
            e.put("toText", text(e.get("to_status"), ORDER_STATUS));
        }
        List<Map<String, Object>> payments = jdbcTemplate.queryForList(
                "SELECT payment_no, pay_type, amount, status, paid_at, created_at FROM my_xhs_payment.t_payment"
                        + " WHERE order_id = ? AND user_id = ? ORDER BY id DESC", orderId, userId);
        for (Map<String, Object> p : payments) {
            p.put("statusText", text(p.get("status"), PAY_STATUS));
        }
        List<Map<String, Object>> refunds = jdbcTemplate.queryForList(
                "SELECT refund_no, refund_amount, status, refund_type, refund_channel, reason, success_at, created_at"
                        + " FROM my_xhs_payment.t_refund WHERE order_id = ? AND user_id = ? ORDER BY id DESC", orderId, userId);
        for (Map<String, Object> r : refunds) {
            r.put("statusText", text(r.get("status"), REFUND_STATUS));
        }
        List<Map<String, Object>> prededuct = jdbcTemplate.queryForList(
                "SELECT sku_id, created_at FROM my_xhs_inventory.t_inventory_prededuct_idem WHERE order_id = ?", orderId);
        List<Map<String, Object>> notifications = jdbcTemplate.queryForList(
                "SELECT type, target_type, title, is_read, created_at FROM my_xhs_notification.t_notification"
                        + " WHERE user_id = ? AND target_id = ? ORDER BY id DESC LIMIT 10", userId, orderId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("shard", "db" + db + ".t_order_" + table);
        result.put("order", order);
        result.put("items", items);
        result.put("events", events);
        result.put("payments", payments);
        result.put("refunds", refunds);
        result.put("inventoryPrededuct", prededuct);
        result.put("notifications", notifications);
        return result;
    }

    public Map<String, Object> orderStats(int hours) {
        int window = Math.max(1, Math.min(720, hours));
        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - window * 3600_000L);
        Map<Integer, Long> counts = new TreeMap<>();
        Map<Integer, BigDecimal> amounts = new TreeMap<>();
        long total = 0;
        BigDecimal gmv = BigDecimal.ZERO;
        for (int db = 0; db < 4; db++) {
            for (int t = 0; t < 4; t++) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT status, COUNT(*) c, COALESCE(SUM(pay_amount),0) amt FROM my_xhs_order_" + db + ".t_order_" + t
                                + " WHERE created_at >= ? GROUP BY status", cutoff);
                for (Map<String, Object> row : rows) {
                    int st = ((Number) row.get("status")).intValue();
                    long c = ((Number) row.get("c")).longValue();
                    BigDecimal amt = row.get("amt") == null ? BigDecimal.ZERO : new BigDecimal(row.get("amt").toString());
                    counts.merge(st, c, Long::sum);
                    amounts.merge(st, amt, BigDecimal::add);
                    total += c;
                    if (st == 1 || st == 2 || st == 3) {
                        gmv = gmv.add(amt);
                    }
                }
            }
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            byStatus.put(e.getKey() + "-" + ORDER_STATUS.getOrDefault(e.getKey(), "未知"), e.getValue());
        }
        Map<Integer, Long> payCounts = new TreeMap<>();
        List<Map<String, Object>> payRows = jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) c FROM my_xhs_payment.t_payment WHERE created_at >= ? GROUP BY status", cutoff);
        for (Map<String, Object> row : payRows) {
            payCounts.put(((Number) row.get("status")).intValue(), ((Number) row.get("c")).longValue());
        }
        Map<String, Long> payByStatus = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : payCounts.entrySet()) {
            payByStatus.put(e.getKey() + "-" + PAY_STATUS.getOrDefault(e.getKey(), "未知"), e.getValue());
        }
        BigDecimal refundSuccess = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount),0) FROM my_xhs_payment.t_refund WHERE created_at >= ? AND status = 1",
                BigDecimal.class, cutoff);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowHours", window);
        result.put("totalOrders", total);
        result.put("byStatus", byStatus);
        result.put("gmvPaid", gmv);
        result.put("paymentsByStatus", payByStatus);
        result.put("refundSuccessAmount", refundSuccess);
        return result;
    }

    public Map<String, Object> inventoryQuery(long skuId) {
        List<Map<String, Object>> sku = jdbcTemplate.queryForList(
                "SELECT id, name, price, stock, status FROM my_xhs_product.t_sku WHERE id = ?", skuId);
        List<Map<String, Object>> inventory = jdbcTemplate.queryForList(
                "SELECT available_stock, locked_stock, freezing_stock, updated_at FROM my_xhs_inventory.t_inventory WHERE sku_id = ?", skuId);
        List<Map<String, Object>> freeze = jdbcTemplate.queryForList(
                "SELECT xid, branch_id, quantity, status, updated_at FROM my_xhs_inventory.t_tcc_freeze_detail"
                        + " WHERE sku_id = ? ORDER BY created_at DESC LIMIT 5", skuId);
        List<Map<String, Object>> compensation = jdbcTemplate.queryForList(
                "SELECT order_id, quantity, status, retry_count, fail_reason, created_at FROM my_xhs_inventory.t_inventory_compensation"
                        + " WHERE sku_id = ? ORDER BY created_at DESC LIMIT 5", skuId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skuId", skuId);
        result.put("sku", sku.isEmpty() ? null : sku.get(0));
        result.put("inventory", inventory.isEmpty() ? null : inventory.get(0));
        result.put("tccFreezeRecent", freeze);
        result.put("compensationRecent", compensation);
        return result;
    }

    public Map<String, Object> couponQuery(long userId) {
        List<Map<String, Object>> coupons = jdbcTemplate.queryForList(
                "SELECT coupon_id, claim_no, status, received_at, used_at, used_order_id FROM my_xhs_coupon.t_user_coupon"
                        + " WHERE user_id = ? ORDER BY id DESC LIMIT 20", userId);
        if (!coupons.isEmpty()) {
            List<Long> ids = coupons.stream().map(c -> ((Number) c.get("coupon_id")).longValue()).distinct().toList();
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            List<Map<String, Object>> templates = jdbcTemplate.queryForList(
                    "SELECT id, name, type, discount_value, min_amount, valid_start, valid_end, status"
                            + " FROM my_xhs_coupon.t_coupon_template WHERE id IN (" + placeholders + ")", ids.toArray());
            Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> t : templates) {
                byId.put(((Number) t.get("id")).longValue(), t);
            }
            for (Map<String, Object> c : coupons) {
                Map<String, Object> t = byId.get(((Number) c.get("coupon_id")).longValue());
                if (t != null) {
                    c.put("templateName", t.get("name"));
                    c.put("discountValue", t.get("discount_value"));
                    c.put("minAmount", t.get("min_amount"));
                    c.put("validEnd", t.get("valid_end"));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("count", coupons.size());
        result.put("coupons", coupons);
        return result;
    }

    private String text(Object status, Map<Integer, String> mapping) {
        if (status == null) {
            return null;
        }
        int st = ((Number) status).intValue();
        return mapping.getOrDefault(st, "未知(" + st + ")");
    }
}
