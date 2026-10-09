package com.myxhs.payment.repository;

import com.myxhs.payment.entity.ChannelFlow;
import com.myxhs.payment.entity.SettlementBill;
import com.myxhs.payment.entity.SettlementDiff;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算域 Repository（账单 / 对账差异 / 渠道流水）
 * <p>
 * 与 t_payment / t_refund 同库（my_xhs_payment），因此直接用 paymentJdbcTemplate 做聚合查询。
 * </p>
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementRepository(@Qualifier("paymentJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 行映射 ====================

    private static final RowMapper<SettlementBill> BILL_MAPPER = (rs, rowNum) -> {
        SettlementBill b = new SettlementBill();
        b.setId(rs.getLong("id"));
        b.setBillDate(rs.getDate("bill_date").toLocalDate());
        b.setChannel(rs.getInt("channel"));
        b.setPayCount(rs.getInt("pay_count"));
        b.setPayAmount(rs.getBigDecimal("pay_amount"));
        b.setRefundCount(rs.getInt("refund_count"));
        b.setRefundAmount(rs.getBigDecimal("refund_amount"));
        b.setNetAmount(rs.getBigDecimal("net_amount"));
        b.setFeeRate(rs.getBigDecimal("fee_rate"));
        b.setFeeAmount(rs.getBigDecimal("fee_amount"));
        b.setSettleAmount(rs.getBigDecimal("settle_amount"));
        b.setStatus(rs.getInt("status"));
        b.setRunNo(rs.getInt("run_no"));
        b.setDiffCount(rs.getInt("diff_count"));
        b.setDiffAmount(rs.getBigDecimal("diff_amount"));
        b.setGeneratedAt(rs.getTimestamp("generated_at") != null ? rs.getTimestamp("generated_at").toLocalDateTime() : null);
        b.setReconciledAt(rs.getTimestamp("reconciled_at") != null ? rs.getTimestamp("reconciled_at").toLocalDateTime() : null);
        b.setRemark(rs.getString("remark"));
        return b;
    };

    private static final RowMapper<SettlementDiff> DIFF_MAPPER = (rs, rowNum) -> {
        SettlementDiff d = new SettlementDiff();
        d.setId(rs.getLong("id"));
        d.setBillDate(rs.getDate("bill_date").toLocalDate());
        d.setChannel(rs.getInt("channel"));
        d.setBizType(rs.getInt("biz_type"));
        d.setDiffType(rs.getInt("diff_type"));
        d.setLocalNo(rs.getString("local_no"));
        d.setChannelNo(rs.getString("channel_no"));
        d.setLocalAmount(rs.getBigDecimal("local_amount"));
        d.setChannelAmount(rs.getBigDecimal("channel_amount"));
        d.setDiffAmount(rs.getBigDecimal("diff_amount"));
        d.setStatus(rs.getInt("status"));
        d.setHandleRemark(rs.getString("handle_remark"));
        d.setHandledAt(rs.getTimestamp("handled_at") != null ? rs.getTimestamp("handled_at").toLocalDateTime() : null);
        return d;
    };

    private static final RowMapper<ChannelFlow> FLOW_MAPPER = (rs, rowNum) -> {
        ChannelFlow f = new ChannelFlow();
        f.setId(rs.getLong("id"));
        f.setBillDate(rs.getDate("bill_date").toLocalDate());
        f.setChannel(rs.getInt("channel"));
        f.setChannelNo(rs.getString("channel_no"));
        f.setBizType(rs.getInt("biz_type"));
        f.setLocalNo(rs.getString("local_no"));
        f.setAmount(rs.getBigDecimal("amount"));
        f.setTradeTime(rs.getTimestamp("trade_time") != null ? rs.getTimestamp("trade_time").toLocalDateTime() : null);
        f.setSource(rs.getString("source"));
        return f;
    };

    // ==================== 账单 ====================

    public SettlementBill findBill(LocalDate billDate, Integer channel) {
        List<SettlementBill> list = jdbcTemplate.query(
                "SELECT * FROM t_settlement_bill WHERE bill_date = ? AND channel = ? LIMIT 1", BILL_MAPPER, billDate, channel);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<SettlementBill> listBills(LocalDate billDate) {
        return jdbcTemplate.query("SELECT * FROM t_settlement_bill WHERE bill_date = ? ORDER BY channel ASC",
                BILL_MAPPER, billDate);
    }

    public int insertBill(SettlementBill b) {
        return jdbcTemplate.update("INSERT INTO t_settlement_bill (bill_date, channel, pay_count, pay_amount,"
                        + " refund_count, refund_amount, net_amount, fee_rate, fee_amount, settle_amount,"
                        + " status, run_no, generated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                b.getBillDate(), b.getChannel(), b.getPayCount(), b.getPayAmount(), b.getRefundCount(),
                b.getRefundAmount(), b.getNetAmount(), b.getFeeRate(), b.getFeeAmount(), b.getSettleAmount(),
                b.getStatus(), b.getRunNo());
    }

    /** 重跑覆盖：仅当当前状态与预期一致（条件更新，防与对账并发互相覆盖） */
    public int regenerateBill(Long id, int expectStatus, SettlementBill b) {
        return jdbcTemplate.update("UPDATE t_settlement_bill SET pay_count = ?, pay_amount = ?, refund_count = ?,"
                        + " refund_amount = ?, net_amount = ?, fee_rate = ?, fee_amount = ?, settle_amount = ?,"
                        + " status = ?, run_no = run_no + 1, diff_count = 0, diff_amount = 0,"
                        + " reconciled_at = NULL, generated_at = NOW() WHERE id = ? AND status = ?",
                b.getPayCount(), b.getPayAmount(), b.getRefundCount(), b.getRefundAmount(), b.getNetAmount(),
                b.getFeeRate(), b.getFeeAmount(), b.getSettleAmount(), SettlementBill.STATUS_GENERATED,
                id, expectStatus);
    }

    /** 对账落定：回填差异汇总并置状态 */
    public int markReconciled(Long id, int expectStatus, int targetStatus, int diffCount, BigDecimal diffAmount) {
        return jdbcTemplate.update("UPDATE t_settlement_bill SET status = ?, diff_count = ?, diff_amount = ?,"
                        + " reconciled_at = NOW() WHERE id = ? AND status = ?",
                targetStatus, diffCount, diffAmount, id, expectStatus);
    }

    /** 作废账单：已生成/有差异 → 作废（需重开时直接重新生成即可，VOID 不在重跑拦截名单内） */
    public int voidBill(Long id, int expectStatus, String remark) {
        return jdbcTemplate.update("UPDATE t_settlement_bill SET status = ?, remark = ?"
                        + " WHERE id = ? AND status = ?",
                SettlementBill.STATUS_VOID, remark, id, expectStatus);
    }

    // ==================== 对账差异 ====================

    /**
     * 幂等写入差异：重复对账只刷新金额；两条业务规则：
     * 1. 人工已处理（status=1）的差异不被覆盖（尊重人工结论）；
     * 2. 曾被自动收敛为"已忽略"（status=2）的差异<b>重新出现时复位为待处理</b>——
     *    否则差异消失一次后就永久静默，形成漏报。
     *    注意 MySQL 的 ON DUPLICATE KEY UPDATE 按左右顺序求值，status 的复位必须放在最后，
     *    前面的表达式才能读到旧 status。
     */
    public int upsertDiff(SettlementDiff d) {
        return jdbcTemplate.update("INSERT INTO t_settlement_diff (bill_date, channel, biz_type, diff_type,"
                        + " local_no, channel_no, local_amount, channel_amount, diff_amount, status)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,0) ON DUPLICATE KEY UPDATE"
                        + " handle_remark = IF(status = 2, NULL, handle_remark),"
                        + " handled_at = IF(status = 2, NULL, handled_at),"
                        + " local_amount = VALUES(local_amount), channel_amount = VALUES(channel_amount),"
                        + " diff_amount = VALUES(diff_amount),"
                        + " status = IF(status = 2, 0, status)",
                d.getBillDate(), d.getChannel(), d.getBizType(), d.getDiffType(), d.getLocalNo(), d.getChannelNo(),
                d.getLocalAmount(), d.getChannelAmount(), d.getDiffAmount());
    }

    public List<SettlementDiff> listDiffs(LocalDate billDate, Integer channel, Integer status) {
        String sql = "SELECT * FROM t_settlement_diff WHERE bill_date = ? AND channel = ?"
                + (status == null ? "" : " AND status = ?") + " ORDER BY diff_amount DESC, id ASC";
        return status == null
                ? jdbcTemplate.query(sql, DIFF_MAPPER, billDate, channel)
                : jdbcTemplate.query(sql, DIFF_MAPPER, billDate, channel, status);
    }

    /** 自动收敛：本轮对账已无差异、但仍挂账的待处理差异置为"已忽略" */
    public int autoConverge(LocalDate billDate, Integer channel, List<SettlementDiff> activeDiffs) {
        StringBuilder sql = new StringBuilder("UPDATE t_settlement_diff SET status = "
                + SettlementDiff.STATUS_IGNORED + ", handle_remark = '对账已无差异(自动收敛)', handled_at = NOW()"
                + " WHERE bill_date = ? AND channel = ? AND status = " + SettlementDiff.STATUS_PENDING);
        List<Object> args = new java.util.ArrayList<>(List.of(billDate, channel));
        if (!activeDiffs.isEmpty()) {
            sql.append(" AND (biz_type, diff_type, local_no, channel_no) NOT IN (");
            for (int i = 0; i < activeDiffs.size(); i++) {
                SettlementDiff d = activeDiffs.get(i);
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("(?,?,?,?)");
                args.add(d.getBizType());
                args.add(d.getDiffType());
                args.add(d.getLocalNo());
                args.add(d.getChannelNo());
            }
            sql.append(')');
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    public SettlementDiff findDiff(Long id) {
        List<SettlementDiff> list = jdbcTemplate.query(
                "SELECT * FROM t_settlement_diff WHERE id = ? LIMIT 1", DIFF_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 人工处理挂账：待处理 → 已处理（条件更新，重复点击幂等） */
    public int handleDiff(Long id, String remark) {
        return jdbcTemplate.update("UPDATE t_settlement_diff SET status = ?, handle_remark = ?, handled_at = NOW()"
                + " WHERE id = ? AND status = ?", SettlementDiff.STATUS_HANDLED, remark, id, SettlementDiff.STATUS_PENDING);
    }

    public int deleteDiffs(LocalDate billDate, Integer channel) {
        return jdbcTemplate.update("DELETE FROM t_settlement_diff WHERE bill_date = ? AND channel = ?", billDate, channel);
    }

    // ==================== 渠道流水 ====================

    /** 幂等导入：同一渠道流水号重复导入只刷新金额/时间 */
    public int upsertChannelFlow(ChannelFlow f) {
        return jdbcTemplate.update("INSERT INTO t_channel_flow (bill_date, channel, channel_no, biz_type, local_no,"
                        + " amount, trade_time, source) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                        + " local_no = VALUES(local_no), amount = VALUES(amount), trade_time = VALUES(trade_time),"
                        + " bill_date = VALUES(bill_date), source = VALUES(source)",
                f.getBillDate(), f.getChannel(), f.getChannelNo(), f.getBizType(), f.getLocalNo(),
                f.getAmount(), f.getTradeTime(), f.getSource());
    }

    public List<ChannelFlow> listChannelFlows(LocalDate billDate, Integer channel) {
        return jdbcTemplate.query("SELECT * FROM t_channel_flow WHERE bill_date = ? AND channel = ?"
                + " ORDER BY trade_time ASC, id ASC", FLOW_MAPPER, billDate, channel);
    }

    public int deleteChannelFlows(LocalDate billDate, Integer channel) {
        return jdbcTemplate.update("DELETE FROM t_channel_flow WHERE bill_date = ? AND channel = ?", billDate, channel);
    }

    /** 清理演练生成的渠道流水（演练重跑时整体替换） */
    public int deleteSimulatedFlows(LocalDate billDate, Integer channel) {
        return jdbcTemplate.update("DELETE FROM t_channel_flow WHERE bill_date = ? AND channel = ? AND source = 'simulate'",
                billDate, channel);
    }

    // ==================== 本地流水（收款/退款） ====================

    /** 金额汇总（笔数 + 金额），账单聚合用 SQL 计算，避免把明细拉进 JVM */
    public record AmountSummary(int count, BigDecimal amount) {
    }

    private static final String PAY_SELECT = "SELECT id, payment_no AS local_no, amount, paid_at AS trade_time"
            + " FROM t_payment WHERE pay_type = ? AND status IN (1, 3) AND deleted = 0"
            + " AND paid_at >= ? AND paid_at < ?";
    private static final String REFUND_SELECT = "SELECT r.id AS id, r.refund_no AS local_no, r.refund_amount AS amount,"
            + " r.success_at AS trade_time FROM t_refund r JOIN t_payment p ON r.payment_id = p.id"
            + " WHERE p.pay_type = ? AND r.status = 1 AND r.deleted = 0 AND p.deleted = 0"
            + " AND r.success_at >= ? AND r.success_at < ?";

    private RowMapper<ChannelFlow> flowMapper(int bizType, LocalDate billDate, Integer channel) {
        return (rs, rowNum) -> {
            ChannelFlow f = new ChannelFlow();
            f.setId(rs.getLong("id"));
            f.setBillDate(billDate);
            f.setChannel(channel);
            f.setBizType(bizType);
            f.setChannelNo("");
            f.setLocalNo(rs.getString("local_no"));
            f.setAmount(rs.getBigDecimal("amount"));
            f.setTradeTime(rs.getTimestamp("trade_time").toLocalDateTime());
            return f;
        };
    }

    /** 当日成功收款汇总（走 idx_pay_type_paid_at 索引，SQL 聚合不拉明细） */
    public AmountSummary aggregatePays(Integer channel, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS amt FROM t_payment"
                        + " WHERE pay_type = ? AND status IN (1, 3) AND deleted = 0 AND paid_at >= ? AND paid_at < ?",
                (rs, rowNum) -> new AmountSummary(rs.getInt("cnt"), rs.getBigDecimal("amt")),
                channel, from, to);
    }

    /** 当日成功退款汇总 */
    public AmountSummary aggregateRefunds(Integer channel, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) AS cnt, COALESCE(SUM(r.refund_amount), 0) AS amt FROM t_refund r"
                        + " JOIN t_payment p ON r.payment_id = p.id"
                        + " WHERE p.pay_type = ? AND r.status = 1 AND r.deleted = 0 AND p.deleted = 0"
                        + " AND r.success_at >= ? AND r.success_at < ?",
                (rs, rowNum) -> new AmountSummary(rs.getInt("cnt"), rs.getBigDecimal("amt")),
                channel, from, to);
    }

    /**
     * 分页扫描当日收款（游标 = (paid_at, id)，配合 idx_pay_type_paid_at 有序扫描，无 filesort）
     */
    public List<ChannelFlow> pageLocalPays(Integer channel, LocalDateTime from, LocalDateTime to,
                                           LocalDateTime afterTime, Long afterId, int limit) {
        return jdbcTemplate.query(PAY_SELECT + " AND (paid_at > ? OR (paid_at = ? AND id > ?))"
                        + " ORDER BY paid_at ASC, id ASC LIMIT ?",
                flowMapper(ChannelFlow.BIZ_PAY, null, channel),
                channel, from, to, afterTime, afterTime, afterId, limit);
    }

    /**
     * 分页扫描当日退款（游标 = (success_at, id)，走 idx_status_success_at）
     */
    public List<ChannelFlow> pageLocalRefunds(Integer channel, LocalDateTime from, LocalDateTime to,
                                              LocalDateTime afterTime, Long afterId, int limit) {
        return jdbcTemplate.query(REFUND_SELECT + " AND (r.success_at > ? OR (r.success_at = ? AND r.id > ?))"
                        + " ORDER BY r.success_at ASC, r.id ASC LIMIT ?",
                flowMapper(ChannelFlow.BIZ_REFUND, null, channel),
                channel, from, to, afterTime, afterTime, afterId, limit);
    }

    /** 按支付单号精确查当日收款（对账按渠道行驱动，避免全量加载本地流水） */
    public ChannelFlow findLocalPay(Integer channel, String paymentNo, LocalDateTime from, LocalDateTime to) {
        List<ChannelFlow> list = jdbcTemplate.query(PAY_SELECT + " AND payment_no = ? LIMIT 1",
                flowMapper(ChannelFlow.BIZ_PAY, null, channel), channel, from, to, paymentNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按退款单号精确查当日退款 */
    public ChannelFlow findLocalRefund(Integer channel, String refundNo, LocalDateTime from, LocalDateTime to) {
        List<ChannelFlow> list = jdbcTemplate.query(REFUND_SELECT + " AND r.refund_no = ? LIMIT 1",
                flowMapper(ChannelFlow.BIZ_REFUND, null, channel), channel, from, to, refundNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 演练用：取当日流水（带上限，仅供生成演练对账单） */
    public List<ChannelFlow> listLocalPays(LocalDate billDate, Integer channel, LocalDateTime from,
                                           LocalDateTime to, int limit) {
        return jdbcTemplate.query(PAY_SELECT + " ORDER BY paid_at ASC, id ASC LIMIT ?",
                flowMapper(ChannelFlow.BIZ_PAY, billDate, channel), channel, from, to, limit);
    }

    public List<ChannelFlow> listLocalRefunds(LocalDate billDate, Integer channel, LocalDateTime from,
                                              LocalDateTime to, int limit) {
        return jdbcTemplate.query(REFUND_SELECT + " ORDER BY r.success_at ASC, r.id ASC LIMIT ?",
                flowMapper(ChannelFlow.BIZ_REFUND, billDate, channel), channel, from, to, limit);
    }
}
