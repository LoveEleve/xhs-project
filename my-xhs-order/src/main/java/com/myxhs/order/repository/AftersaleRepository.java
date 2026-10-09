package com.myxhs.order.repository;

import com.myxhs.order.entity.Aftersale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 售后单 Repository
 * <p>
 * 表位于 my_xhs_order 公共库（不走 ShardingSphere），与订单号映射同一独立数据源，
 * 因此统一用 JdbcTemplate 访问，避免被分片路由拦截。
 * </p>
 * <p>
 * 并发控制：所有状态流转都是条件更新（{@code WHERE id=? AND status=?}），
 * 返回 0 表示状态已被其他请求改变，调用方据此判定冲突（与订单状态机同一套写法）。
 * </p>
 */
@Repository
public class AftersaleRepository {

    private final JdbcTemplate jdbcTemplate;

    public AftersaleRepository(@Qualifier("mappingJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Aftersale> ROW_MAPPER = (rs, rowNum) -> {
        Aftersale a = new Aftersale();
        a.setId(rs.getLong("id"));
        a.setAftersaleNo(rs.getString("aftersale_no"));
        a.setOrderId(rs.getLong("order_id"));
        a.setOrderNo(rs.getString("order_no"));
        a.setUserId(rs.getLong("user_id"));
        a.setSkuId(rs.getLong("sku_id"));
        a.setSkuName(rs.getString("sku_name"));
        a.setType(rs.getInt("type"));
        a.setStatus(rs.getInt("status"));
        a.setApplyQuantity(rs.getInt("apply_quantity"));
        a.setItemAmount(rs.getBigDecimal("item_amount"));
        a.setDiscountShare(rs.getBigDecimal("discount_share"));
        a.setRefundAmount(rs.getBigDecimal("refund_amount"));
        a.setReason(rs.getString("reason"));
        a.setRejectReason(rs.getString("reject_reason"));
        a.setReturnWaybill(rs.getString("return_waybill"));
        a.setRefundNo(rs.getString("refund_no"));
        a.setRestockStatus(rs.getInt("restock_status"));
        a.setRetryCount(rs.getInt("retry_count"));
        a.setAppliedAt(rs.getTimestamp("applied_at") != null ? rs.getTimestamp("applied_at").toLocalDateTime() : null);
        a.setAuditedAt(rs.getTimestamp("audited_at") != null ? rs.getTimestamp("audited_at").toLocalDateTime() : null);
        a.setFinishedAt(rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toLocalDateTime() : null);
        a.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
        return a;
    };

    private static final String COLUMNS = "id, aftersale_no, order_id, order_no, user_id, sku_id, sku_name, type, status,"
            + " apply_quantity, item_amount, discount_share, refund_amount, reason, reject_reason, return_waybill,"
            + " refund_no, restock_status, retry_count, applied_at, audited_at, finished_at, updated_at";

    public int insert(Aftersale a) {
        return jdbcTemplate.update(
                "INSERT INTO t_aftersale (aftersale_no, order_id, order_no, user_id, sku_id, sku_name, type, status,"
                        + " apply_quantity, item_amount, discount_share, refund_amount, reason, return_waybill)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                a.getAftersaleNo(), a.getOrderId(), a.getOrderNo(), a.getUserId(), a.getSkuId(), a.getSkuName(),
                a.getType(), a.getStatus(), a.getApplyQuantity(), a.getItemAmount(), a.getDiscountShare(),
                a.getRefundAmount(), a.getReason(), a.getReturnWaybill());
    }

    public Aftersale findByNo(String aftersaleNo) {
        return queryOne("SELECT " + COLUMNS + " FROM t_aftersale WHERE aftersale_no = ? LIMIT 1", aftersaleNo);
    }

    public Aftersale findByOrderSkuType(Long orderId, Long skuId, Integer type) {
        return queryOne("SELECT " + COLUMNS + " FROM t_aftersale WHERE order_id = ? AND sku_id = ? AND type = ? LIMIT 1",
                orderId, skuId, type);
    }

    public List<Aftersale> listByUser(Long userId, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM t_aftersale WHERE user_id = ?"
                + " ORDER BY applied_at DESC LIMIT ?", ROW_MAPPER, userId, limit);
    }

    public List<Aftersale> listByOrder(Long orderId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM t_aftersale WHERE order_id = ?"
                + " ORDER BY applied_at ASC", ROW_MAPPER, orderId);
    }

    /** 已占用退款额度：退款中/已完成 的应退金额之和（防止跨单/多次申请累计超退） */
    public BigDecimal sumRefundedAmount(Long orderId) {
        BigDecimal sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM t_aftersale WHERE order_id = ? AND status IN (3, 4)",
                BigDecimal.class, orderId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 某订单某 SKU 已占用退款额度（退款中/已完成）
     * <p>
     * 关键：按 (order_id, sku_id) 汇总而<b>不区分售后类型</b>——
     * 只按类型汇总时，"仅退款"和"退货退款"两张单会各自退满一次，导致同一商品被退两次（超退）。
     * </p>
     */
    public BigDecimal sumRefundedAmountBySku(Long orderId, Long skuId) {
        BigDecimal sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM t_aftersale"
                        + " WHERE order_id = ? AND sku_id = ? AND status IN (3, 4)",
                BigDecimal.class, orderId, skuId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 已被售后单认领的支付域退款单号（一个成功退款只能归属一张售后单，防止同额退款张冠李戴） */
    public java.util.Set<String> usedRefundNos(Long orderId) {
        List<String> nos = jdbcTemplate.queryForList(
                "SELECT refund_no FROM t_aftersale WHERE order_id = ? AND refund_no IS NOT NULL",
                String.class, orderId);
        return new java.util.HashSet<>(nos);
    }

    /** 审核：待审核 → 已同意 / 已拒绝（条件更新，带审核时间） */
    public int updateOnAudit(Long id, int expectStatus, int targetStatus, String rejectReason) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = ?, reject_reason = ?, audited_at = NOW()"
                + " WHERE id = ? AND status = ?", targetStatus, rejectReason, id, expectStatus);
    }

    /** 退款成功：退款中 → 已完成（带完成时间与支付域退款单号归属） */
    public int updateOnRefundSuccess(Long id, int expectStatus, int targetStatus, String refundNo) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = ?, refund_no = ?, finished_at = NOW()"
                + " WHERE id = ? AND status = ?", targetStatus, refundNo, id, expectStatus);
    }

    /** 退款失败：退款中 → 退款失败（累计重试次数） */
    public int updateOnRefundFail(Long id, int expectStatus, int targetStatus) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = ?, retry_count = retry_count + 1"
                + " WHERE id = ? AND status = ?", targetStatus, id, expectStatus);
    }

    /** 某订单某 SKU 已退数量（退款中/已完成），用于"退满补齐差额"的账目闭合 */
    public int sumRefundedQuantityBySku(Long orderId, Long skuId) {
        Integer sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(apply_quantity), 0) FROM t_aftersale"
                        + " WHERE order_id = ? AND sku_id = ? AND status IN (3, 4)",
                Integer.class, orderId, skuId);
        return sum == null ? 0 : sum;
    }

    /** 标记库存已回补（条件更新：仅"待回补"可置，重复调用幂等） */
    public int markRestocked(Long id) {
        return jdbcTemplate.update("UPDATE t_aftersale SET restock_status = 1 WHERE id = ? AND restock_status = 0", id);
    }

    /** 已完成但库存未回补的售后单（跨服务回补失败的兜底扫描） */
    public List<Aftersale> listPendingRestock(int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM t_aftersale WHERE status = "
                + Aftersale.STATUS_FINISHED + " AND restock_status = 0 ORDER BY id ASC LIMIT ?", ROW_MAPPER, limit);
    }

    /**
     * 按状态扫描（恢复任务用）：ORDER BY id（自增主键，插入顺序≈时间顺序）
     * <p>
     * 不用 ORDER BY COALESCE(audited_at, applied_at)：函数排序无法走索引，会触发 filesort，
     * 恢复任务每 10 分钟跑一次，挂账多时排序成本会被放大。
     * </p>
     */
    public List<Aftersale> listByStatus(int status, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM t_aftersale WHERE status = ?"
                + " ORDER BY id ASC LIMIT ?", ROW_MAPPER, status, limit);
    }

    /** 卡死转失败：退款中 → 退款失败（累计重试次数），交给自动重试分支 */
    public int markStuckFailed(Long id) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = ?, retry_count = retry_count + 1"
                + " WHERE id = ? AND status = ?", Aftersale.STATUS_REFUND_FAILED, id, Aftersale.STATUS_REFUNDING);
    }

    /** 退款失败重试：退款失败 → 退款中 */
    public int updateOnRetry(Long id) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = ? WHERE id = ? AND status = ?",
                Aftersale.STATUS_REFUNDING, id, Aftersale.STATUS_REFUND_FAILED);
    }

    /** 用户撤销：仅待审核可撤 */
    public int cancel(Long id, int expectStatus, int targetStatus) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = ?, finished_at = NOW() WHERE id = ? AND status = ?",
                targetStatus, id, expectStatus);
    }

    /** 驳回/取消后重新申请：业务字段整体覆盖，状态回到待审核，清空上轮审核与退款痕迹 */
    public int reapply(Long id, int expectStatus, Aftersale a) {
        return jdbcTemplate.update("UPDATE t_aftersale SET status = 0, apply_quantity = ?, item_amount = ?,"
                        + " discount_share = ?, refund_amount = ?, reason = ?, return_waybill = ?,"
                        + " reject_reason = NULL, refund_no = NULL, retry_count = 0, audited_at = NULL,"
                        + " finished_at = NULL, applied_at = NOW() WHERE id = ? AND status = ?",
                a.getApplyQuantity(), a.getItemAmount(), a.getDiscountShare(), a.getRefundAmount(),
                a.getReason(), a.getReturnWaybill(), id, expectStatus);
    }

    private Aftersale queryOne(String sql, Object... args) {
        List<Aftersale> list = jdbcTemplate.query(sql, ROW_MAPPER, args);
        return list.isEmpty() ? null : list.get(0);
    }
}
