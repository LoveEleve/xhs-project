package com.myxhs.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 标记单条已读（乐观锁：只更新未读的）
     */
    @Update("UPDATE t_notification SET is_read = 1, updated_at = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND is_read = 0 AND deleted = 0")
    int markAsRead(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 按类型全部标记已读
     */
    @Update("UPDATE t_notification SET is_read = 1, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND type = #{type} AND is_read = 0 AND deleted = 0")
    int markAllReadByType(@Param("userId") Long userId, @Param("type") Integer type);

    /**
     * 全部标记已读
     */
    @Update("UPDATE t_notification SET is_read = 1, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND is_read = 0 AND deleted = 0")
    int markAllAsRead(@Param("userId") Long userId);

    /**
     * 更新聚合计数和标题
     */
    @Update("UPDATE t_notification SET aggregate_count = #{count}, title = #{title}, updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int updateAggregateInfo(@Param("id") Long id, @Param("count") int count, @Param("title") String title);

    /**
     * 原子递增聚合计数（解决并发竞态：aggregate_count = aggregate_count + 1）
     * <p>
     * 返回更新后的 aggregate_count 值。
     * 如果记录不存在返回 0。
     * </p>
     */
    @Update("UPDATE t_notification SET aggregate_count = aggregate_count + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int incrementAggregateCount(@Param("id") Long id);

    /** 查询聚合计数（修复 incrementAggregateCount 返回值误用问题） */
    @Select("SELECT aggregate_count FROM t_notification WHERE id = #{id}")
    Integer getAggregateCount(@Param("id") Long id);

    /**
     * 仅更新聚合标题（与 incrementAggregateCount 配合使用）
     */
    @Update("UPDATE t_notification SET title = #{title}, updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int updateAggregateTitle(@Param("id") Long id, @Param("title") String title);

    /**
     * 订单通知聚合：刷新为最新状态内容（标题保持模板标题，不做"等N人"社交化聚合）
     * <p>
     * 受唯一键 uk_aggregate(user_id,type,target_id,notify_date) 约束，同一订单当天只保留一条；
     * 后到的状态（已发货/退款到账）必须覆盖内容，否则用户会一直看到首条旧状态。
     * </p>
     */
    @Update("UPDATE t_notification SET content = #{content}, extra_data = #{extraData}, updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int updateOrderLatest(@Param("id") Long id,
                          @Param("content") String content,
                          @Param("extraData") String extraData);
    /**
     * 清理保留期前的通知（(user_id, created_at) 索引支撑；LIMIT 分批防长事务）
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM t_notification WHERE created_at < #{before} LIMIT #{limit}")
    int deleteStale(@Param("before") java.time.LocalDateTime before, @Param("limit") int limit);

}
