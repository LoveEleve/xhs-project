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
     *
     * @return 本批次实际更新的行数
     */
    @Update("UPDATE t_user_coupon uc " +
            "INNER JOIN t_coupon_template ct ON uc.coupon_id = ct.id AND ct.deleted = 0 " +
            "SET uc.status = 2 " +
            "WHERE uc.status = 0 AND ct.valid_end < NOW() " +
            "LIMIT #{batchSize}")
    int batchExpire(@Param("batchSize") int batchSize);
}
