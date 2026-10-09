package com.myxhs.coupon.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 优惠券 Outbox Mapper（MQ 发送可靠性保障）
 * <p>领券时先写 Outbox 再发 MQ，失败由 Job 补发。</p>
 */
@Mapper
public interface CouponOutboxMapper {

    /**
     * 写入 Outbox 记录（领券成功后调用）
     */
    @Insert("INSERT INTO t_coupon_outbox (id, user_id, template_id, claim_no, status, created_at) " +
            "VALUES (#{id}, #{userId}, #{templateId}, #{claimNo}, 0, NOW())")
    int insertOutboxEvent(@Param("id") Long id, @Param("userId") Long userId,
            @Param("templateId") Long templateId, @Param("claimNo") String claimNo);

    /**
     * 查询待发送的 Outbox 事件
     */
    @Select("SELECT * FROM t_coupon_outbox WHERE status = 0 AND created_at < #{cutoff} " +
            "AND (next_retry_time IS NULL OR next_retry_time <= NOW()) " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<Map<String, Object>> selectPendingOutbox(@Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    /** 补发失败：递增重试次数并设置下次可发时间（指数退避+抖动，P2/2026-09-27） */
    @Update("UPDATE t_coupon_outbox SET retry_count = retry_count + 1, next_retry_time = #{nextRetryTime} " +
            "WHERE id = #{id}")
    int markOutboxRetry(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /** RV32：清理已发送的历史 Outbox 记录（防表无限增长；失败态保留） */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_coupon_outbox WHERE status IN (1, 2) AND created_at < #{cutoff} LIMIT 5000")
    int deleteSentBefore(@org.apache.ibatis.annotations.Param("cutoff") LocalDateTime cutoff);

    /**
     * 标记 Outbox 事件已发送
     */
    @Update("UPDATE t_coupon_outbox SET status = 1 WHERE claim_no = #{claimNo}")
    int markOutboxSent(@Param("claimNo") String claimNo);

    /**
     * 统计"卡住的 Outbox"：待发送且超过给定时间仍未发出（用户可能被扣限领/库存却拿不到券）
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM t_coupon_outbox "
            + "WHERE status = 0 AND created_at < #{cutoff}")
    Long countStuck(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 作废 Outbox（status=2）：发送失败且调用方已回滚 Redis 计数时必须调用，
     * 否则补发任务会再次投递 → 消费端发券，而 Redis 已回滚 → 超发（用户可再领）
     */
    @Update("UPDATE t_coupon_outbox SET status = 2 WHERE claim_no = #{claimNo} AND status = 0")
    int markOutboxCancelled(@Param("claimNo") String claimNo);
}
