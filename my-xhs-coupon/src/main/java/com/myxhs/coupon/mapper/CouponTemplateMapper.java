package com.myxhs.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.coupon.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 优惠券模板 Mapper
 */
@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 对账专用：仅更新 remain_count（不碰 status 等其它列，避免覆盖并发扣减）
     * <p>
     * 用原生 SQL 而非 LambdaUpdateWrapper：批量/任务路径不应依赖 MyBatis-Plus 的 lambda 缓存
     * （非完整上下文或单测中会抛 "can not find lambda cache for this entity"）。
     * </p>
     */
    @Update("UPDATE t_coupon_template SET remain_count = #{remainCount}, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updateRemainCountOnly(@Param("id") Long id, @Param("remainCount") int remainCount);

    /** 对账专用：仅更新 status（同上，避免全字段写覆盖并发扣减） */
    @Update("UPDATE t_coupon_template SET status = #{status}, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updateStatusOnly(@Param("id") Long id, @Param("status") int status);

    /**
     * 扣减剩余数量（乐观锁：remain_count > 0）
     * <p>
     * 用于 MQ 异步落库时扣减 MySQL 中的剩余数量。
     * Redis Lua 已保证不超发，MySQL 这里是兜底。
     * </p>
     */
    @Update("UPDATE t_coupon_template SET remain_count = remain_count - 1 " +
            "WHERE id = #{templateId} AND remain_count > 0 AND deleted = 0")
    int decrementRemainCount(@Param("templateId") Long templateId);

    /**
     * 原子回退剩余数量（退券时调用）
     * <p>
     * 使用 SQL 原子操作 remain_count + 1，避免"先读后写"的并发 ABA 问题。
     * 多个退券请求并发时，每个都能正确 +1。
     * </p>
     */
    @Update("UPDATE t_coupon_template SET remain_count = remain_count + 1 " +
            "WHERE id = #{templateId} AND deleted = 0")
    int incrementRemainCount(@Param("templateId") Long templateId);
}
