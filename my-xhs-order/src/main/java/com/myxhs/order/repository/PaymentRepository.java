package com.myxhs.order.repository;

import com.myxhs.order.entity.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 支付表 Repository（使用独立数据源，不走 ShardingSphere）
 * <p>
 * 支付表存储在独立库 my_xhs_payment 中，
 * 使用 JdbcTemplate 操作，避免被 ShardingSphere 拦截路由到分片库。
 * </p>
 */
@Slf4j
@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentRepository(@Qualifier("paymentJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> {
        Payment p = new Payment();
        p.setId(rs.getLong("id"));
        p.setOrderId(rs.getLong("order_id"));
        p.setUserId(rs.getLong("user_id"));
        p.setPaymentNo(rs.getString("payment_no"));
        p.setAmount(rs.getBigDecimal("amount"));
        p.setPayType(rs.getInt("pay_type"));
        p.setStatus(rs.getInt("status"));
        p.setPaidAt(rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at").toLocalDateTime() : null);
        p.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
        return p;
    };

    /**
     * 插入支付记录
     */
    public int insert(Payment payment) {
        return jdbcTemplate.update(
                "INSERT INTO t_payment (id, order_id, user_id, payment_no, amount, pay_type, status, paid_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                payment.getId(), payment.getOrderId(), payment.getUserId(),
                payment.getPaymentNo(), payment.getAmount(), payment.getPayType(),
                payment.getStatus(), payment.getPaidAt());
    }

    /**
     * 通过订单ID查询最新支付记录
     */
    public Payment selectByOrderId(Long orderId) {
        List<Payment> list = jdbcTemplate.query(
                "SELECT * FROM t_payment WHERE order_id = ? AND deleted = 0 ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER, orderId);
        return list.isEmpty() ? null : list.get(0);
    }
}
