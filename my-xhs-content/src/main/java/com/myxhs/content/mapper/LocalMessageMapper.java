package com.myxhs.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.content.entity.LocalMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /**
     * 查询待发送的消息（超时未确认 + 重试未达上限）
     *
     * @param delaySeconds 创建时间距今多少秒后仍未确认
     * @param maxRetry     最大重试次数
     * @param limit        每次扫描上限
     */
    @Select("SELECT * FROM t_local_message " +
            "WHERE status = 0 AND retry_count < #{maxRetry} " +
            "AND created_at < #{cutoffTime} " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<LocalMessage> selectPending(@Param("cutoffTime") LocalDateTime cutoffTime,
                                     @Param("maxRetry") int maxRetry,
                                     @Param("limit") int limit);

    /** 标记为已发送（乐观锁：仅 status=0 时更新） */
    @Update("UPDATE t_local_message SET status = 1 WHERE id = #{id} AND status = 0")
    int markSent(@Param("id") Long id);

    /**
     * 重试次数 +1，超过上限标记为死信
     * <p>
     * 使用 retry_count + 1 判断（避免 MySQL UPDATE 中 CASE 引用旧值的 off-by-one）：
     * 当 retry_count=2 时，retry_count+1=3 >= maxRetry=3 → 标记死信。
     * </p>
     */
    @Update("UPDATE t_local_message SET retry_count = retry_count + 1, " +
            "status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 3 ELSE status END " +
            "WHERE id = #{id}")
    int incrementRetry(@Param("id") Long id, @Param("maxRetry") int maxRetry);

    /**
     * 查询待推送的消息（已发送 MQ 但 Feed 推送未完成）
     */
    @Select("SELECT * FROM t_local_message " +
            "WHERE status = 1 AND push_status IN (0, 1) " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<LocalMessage> selectPendingPush(@Param("limit") int limit);

    /**
     * 查询待推送的消息（已发送 MQ 但 Feed 推送未完成，带延迟过滤）
     * <p>
     * 与 selectPendingPush 的区别：增加 created_at 延迟过滤，避免刚发布的消息立即被补偿。
     * </p>
     */
    @Select("SELECT * FROM t_local_message " +
            "WHERE status = 1 AND push_status IN (0, 1) " +
            "AND created_at < #{cutoffTime} " +
            "AND (push_next_retry_time IS NULL OR push_next_retry_time <= NOW()) " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<LocalMessage> selectPendingPushWithDelay(@Param("cutoffTime") LocalDateTime cutoffTime,
                                                   @Param("limit") int limit);

    /**
     * 更新推送进度
     * @param pushStatus 推送状态: 1=推送中 2=已推送
     * @param pushCursor 已推送到的粉丝游标位置
     */
    @Update("UPDATE t_local_message SET push_status = #{pushStatus}, " +
            "push_cursor = #{pushCursor} WHERE id = #{id}")
    int updatePushProgress(@Param("id") Long id,
                           @Param("pushStatus") int pushStatus,
                           @Param("pushCursor") int pushCursor);

    /**
     * 仅更新推送状态（不修改 cursor，避免补偿任务覆盖 Consumer 的推送进度）
     */
    @Update("UPDATE t_local_message SET push_status = #{pushStatus} WHERE id = #{id}")
    int updatePushStatus(@Param("id") Long id, @Param("pushStatus") int pushStatus);

    /** 补偿推送失败：指数退避+抖动写回下次可推时间（P3/2026-09-27） */
    @Update("UPDATE t_local_message SET push_next_retry_time = #{nextRetryTime} WHERE id = #{id}")
    int updatePushNextRetry(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /**
     * 更新总粉丝数
     */
    @Update("UPDATE t_local_message SET push_total = #{pushTotal} WHERE id = #{id}")
    int updatePushTotal(@Param("id") Long id, @Param("pushTotal") int pushTotal);
}
