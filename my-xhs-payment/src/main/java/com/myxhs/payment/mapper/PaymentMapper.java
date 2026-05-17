package com.myxhs.payment.mapper;

import com.myxhs.payment.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 支付记录 Mapper
 * <p>
 * 使用独立 JdbcTemplate（PaymentDataSourceConfig 配置），
 * 绕过 ShardingSphere 分片路由。
 * </p>
 */
@Mapper
public interface PaymentMapper {

    @Select("SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
            "FROM t_payment WHERE id = #{id} AND deleted = 0")
    Payment selectById(@Param("id") Long id);

    @Select("SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
            "FROM t_payment WHERE order_id = #{orderId} AND deleted = 0 ORDER BY created_at DESC LIMIT 1")
    Payment selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
            "FROM t_payment WHERE payment_no = #{paymentNo} AND deleted = 0")
    Payment selectByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select("SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
            "FROM t_payment WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC")
    List<Payment> selectByUserId(@Param("userId") Long userId);

    @Select("SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
            "FROM t_payment WHERE status = #{status} AND deleted = 0 ORDER BY created_at ASC")
    List<Payment> selectByStatus(@Param("status") Integer status);

    int insert(Payment payment);

    @Update("UPDATE t_payment SET status = #{status}, paid_at = #{paidAt}, updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND status = #{expectStatus}")
    int updateStatusWithOptimisticLock(@Param("id") Long id,
                                       @Param("status") Integer status,
                                       @Param("paidAt") java.time.LocalDateTime paidAt,
                                       @Param("updatedAt") java.time.LocalDateTime updatedAt,
                                       @Param("expectStatus") Integer expectStatus);
}
