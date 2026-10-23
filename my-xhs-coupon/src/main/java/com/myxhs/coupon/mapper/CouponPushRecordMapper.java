package com.myxhs.coupon.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 优惠券推送记录（C-017 P3）：幂等 + 失败重试状态
 */
@Mapper
public interface CouponPushRecordMapper {

    @Insert("INSERT IGNORE INTO t_coupon_push_record " +
            "(id, template_id, user_id, push_date, status, created_at, updated_at) " +
            "VALUES (#{id}, #{templateId}, #{userId}, #{pushDate}, 0, NOW(), NOW())")
    int insertIgnore(@Param("id") long id, @Param("templateId") Long templateId,
                     @Param("userId") Long userId, @Param("pushDate") LocalDate pushDate);

    @Select("SELECT status FROM t_coupon_push_record " +
            "WHERE template_id = #{templateId} AND user_id = #{userId} AND push_date = #{pushDate} LIMIT 1")
    Integer selectStatus(@Param("templateId") Long templateId,
                         @Param("userId") Long userId, @Param("pushDate") LocalDate pushDate);

    @Update("UPDATE t_coupon_push_record SET status = 1, updated_at = NOW() " +
            "WHERE template_id = #{templateId} AND user_id = #{userId} AND push_date = #{pushDate}")
    int markSent(@Param("templateId") Long templateId,
                 @Param("userId") Long userId, @Param("pushDate") LocalDate pushDate);

    @Update("UPDATE t_coupon_push_record SET status = 2, fail_reason = #{reason}, updated_at = NOW() " +
            "WHERE template_id = #{templateId} AND user_id = #{userId} AND push_date = #{pushDate} AND status <> 1")
    int markFailed(@Param("templateId") Long templateId, @Param("userId") Long userId,
                   @Param("pushDate") LocalDate pushDate, @Param("reason") String reason);
}
