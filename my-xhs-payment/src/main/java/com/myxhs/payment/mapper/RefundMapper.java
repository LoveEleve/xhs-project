package com.myxhs.payment.mapper;

import com.myxhs.payment.entity.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款单 Mapper
 * <p>
 * 使用独立 JdbcTemplate，与 PaymentMapper 同库。
 * </p>
 */
@Mapper
public interface RefundMapper {

    @Select("SELECT id, payment_id, order_id, user_id, refund_no, refund_amount, reason, status, refund_type, refund_channel, success_at, deleted, created_at, updated_at " +
            "FROM t_refund WHERE id = #{id} AND deleted = 0")
    Refund selectById(@Param("id") Long id);

    @Select("SELECT id, payment_id, order_id, user_id, refund_no, refund_amount, reason, status, refund_type, refund_channel, success_at, deleted, created_at, updated_at " +
            "FROM t_refund WHERE refund_no = #{refundNo} AND deleted = 0")
    Refund selectByRefundNo(@Param("refundNo") String refundNo);

    @Select("SELECT id, payment_id, order_id, user_id, refund_no, refund_amount, reason, status, refund_type, refund_channel, success_at, deleted, created_at, updated_at " +
            "FROM t_refund WHERE payment_id = #{paymentId} AND status = 1 AND deleted = 0")
    List<Refund> selectSuccessByPaymentId(@Param("paymentId") Long paymentId);

    @Select("SELECT COALESCE(SUM(refund_amount), 0) FROM t_refund " +
            "WHERE payment_id = #{paymentId} AND status = 1 AND deleted = 0")
    BigDecimal sumRefundedAmountByPaymentId(@Param("paymentId") Long paymentId);

    @Select("SELECT id, payment_id, order_id, user_id, refund_no, refund_amount, reason, status, refund_type, refund_channel, success_at, deleted, created_at, updated_at " +
            "FROM t_refund WHERE status = #{status} AND deleted = 0 ORDER BY created_at ASC")
    List<Refund> selectByStatus(@Param("status") Integer status);

    int insert(Refund refund);

    @Update("UPDATE t_refund SET status = #{status}, success_at = #{successAt}, updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND status = #{expectStatus}")
    int updateStatusWithOptimisticLock(@Param("id") Long id,
                                       @Param("status") Integer status,
                                       @Param("successAt") java.time.LocalDateTime successAt,
                                       @Param("updatedAt") java.time.LocalDateTime updatedAt,
                                       @Param("expectStatus") Integer expectStatus);
}
