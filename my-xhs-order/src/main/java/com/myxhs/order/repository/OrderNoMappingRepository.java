package com.myxhs.order.repository;

import com.myxhs.order.entity.OrderNoMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 订单号映射表 Repository（使用独立数据源，不走 ShardingSphere）
 * <p>
 * 该表存储在公共库 my_xhs_order 中，用于通过订单号反查 user_id。
 * 使用 JdbcTemplate 操作，避免被 ShardingSphere 拦截路由到分片库。
 * </p>
 */
@Slf4j
@Repository
public class OrderNoMappingRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderNoMappingRepository(@Qualifier("mappingJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<OrderNoMapping> ROW_MAPPER = (rs, rowNum) -> {
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setId(rs.getLong("id"));
        mapping.setOrderNo(rs.getString("order_no"));
        mapping.setUserId(rs.getLong("user_id"));
        mapping.setOrderId(rs.getLong("order_id"));
        mapping.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return mapping;
    };

    /**
     * 插入映射记录
     */
    public int insert(OrderNoMapping mapping) {
        return jdbcTemplate.update(
                "INSERT INTO t_order_no_mapping (order_no, user_id, order_id) VALUES (?, ?, ?)",
                mapping.getOrderNo(), mapping.getUserId(), mapping.getOrderId());
    }

    /**
     * 通过订单号查询映射
     */
    public OrderNoMapping selectByOrderNo(String orderNo) {
        List<OrderNoMapping> list = jdbcTemplate.query(
                "SELECT * FROM t_order_no_mapping WHERE order_no = ? LIMIT 1",
                ROW_MAPPER, orderNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 通过订单ID查询映射
     */
    public OrderNoMapping selectByOrderId(Long orderId) {
        List<OrderNoMapping> list = jdbcTemplate.query(
                "SELECT * FROM t_order_no_mapping WHERE order_id = ? LIMIT 1",
                ROW_MAPPER, orderId);
        return list.isEmpty() ? null : list.get(0);
    }
}
