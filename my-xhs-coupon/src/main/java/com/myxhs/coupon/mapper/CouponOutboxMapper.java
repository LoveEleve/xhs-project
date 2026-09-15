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
            "ORDER BY id ASC LIMIT #{limit}")
    List<Map<String, Object>> selectPendingOutbox(@Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit);

    /** RV32：清理已发送的历史 Outbox 记录（防表无限增长；失败态保留） */
    @org.apache.ibatis.annotations.Delete("DELETE FROM t_coupon_outbox WHERE status = 1 AND created_at < #{cutoff} LIMIT 5000")
    int deleteSentBefore(@org.apache.ibatis.annotations.Param("cutoff") LocalDateTime cutoff);

    /**
     * 标记 Outbox 事件已发送
     */
    @Update("UPDATE t_coupon_outbox SET status = 1 WHERE claim_no = #{claimNo}")
    int markOutboxSent(@Param("claimNo") String claimNo);
}
