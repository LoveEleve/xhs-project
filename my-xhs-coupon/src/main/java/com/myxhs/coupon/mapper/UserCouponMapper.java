package com.myxhs.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.coupon.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户优惠券 Mapper
 */
@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 限领计数对账专用：统计某用户在某模板下已发的券数（含已使用/已过期；本表无逻辑删除列）
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM t_user_coupon "
            + "WHERE user_id = #{userId} AND coupon_id = #{couponId}")
    Long countIssued(@Param("userId") Long userId, @Param("couponId") Long couponId);

    /**
     * 标记券已使用（乐观锁：WHERE status = 0）
     * <p>
     * 并发用券时只有一个请求能成功（status=0 → 1 是单向状态流转）。
     * </p>
     */
    @Update("UPDATE t_user_coupon SET status = 1, used_order_id = #{orderId}, used_at = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int markUsed(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * 退回券（取消订单时恢复为未使用）
     * <p>
     * 乐观锁：WHERE status = 1 AND used_order_id = orderId
     * 保证幂等：同一订单的退券只能执行一次。
     * </p>
     */
    @Update("UPDATE t_user_coupon SET status = 0, used_order_id = NULL, used_at = NULL " +
            "WHERE id = #{id} AND status = 1 AND used_order_id = #{orderId}")
    int returnCoupon(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * 分批标记过期（每次最多处理 batchSize 条）
     * <p>
     * 为什么要分批？
     * 如果有百万级过期券，一次性 UPDATE 会长时间持有行锁，阻塞其他写操作。
     * 分批处理（每次 1000 条）减少锁持有时间，降低对线上业务的影响。
     * </p>
     * <p>
     * T-058（2026-08-14）：原多表 JOIN UPDATE + LIMIT 为 MySQL 非法语法
     * （Incorrect usage of UPDATE and LIMIT），任务每分钟执行必失败、过期券永不标记。
     * 改为派生表子查询：内层 JOIN 选出过期券 id（LIMIT 分批），外层单表 UPDATE。
     * 派生表二次包装规避"UPDATE 子查询引用同表"限制（MySQL 8.0）。
     * </p>
     *
     * @return 本批次实际更新的行数
     */
    @Update("UPDATE t_user_coupon SET status = 2 " +
            "WHERE status = 0 AND id IN (" +
            "  SELECT id FROM (" +
            "    SELECT uc2.id FROM t_user_coupon uc2 " +
            "    INNER JOIN t_coupon_template ct2 ON uc2.coupon_id = ct2.id AND ct2.deleted = 0 " +
            "    WHERE uc2.status = 0 AND ct2.valid_end < NOW() " +
            "    LIMIT #{batchSize}" +
            "  ) tmp" +
            ")")
    int batchExpire(@Param("batchSize") int batchSize);
}
