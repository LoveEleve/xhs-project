package com.myxhs.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.order.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /** 查询待处理/失败的消息（定时任务补发）
     *  时间窗口保护：只扫描 60 秒前创建的消息，避免与事务消息 Commit 过程冲突 */
    @Select("SELECT * FROM t_local_message WHERE status IN (0, 2) " +
            "AND retry_count < 3 AND created_at < DATE_SUB(NOW(), INTERVAL 60 SECOND) " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<LocalMessage> selectPendingMessages(@Param("limit") int limit);

    /** 标记消息成功 */
    @Update("UPDATE t_local_message SET status = 1, updated_at = NOW() WHERE id = #{id}")
    int markSuccess(@Param("id") Long id);

    /** 标记消息失败并增加重试次数 */
    @Update("UPDATE t_local_message SET status = 2, retry_count = retry_count + 1, updated_at = NOW() " +
            "WHERE id = #{id}")
    int markFailed(@Param("id") Long id);

    /** 标记为死信（重试次数超限） */
    @Update("UPDATE t_local_message SET status = 3, updated_at = NOW() WHERE id = #{id}")
    int markDead(@Param("id") Long id);

    /** T-072（2026-08-14）：补发失败重试/死信状态更新——按 id 广播更新（不碰分片键 user_id，
     *  原 updateById 全字段更新含 user_id 触发 ShardingSphere "can not update sharding value"） */
    @Update("UPDATE t_local_message SET status = #{status}, retry_count = #{retryCount}, " +
            "next_retry_time = #{nextRetryTime}, updated_at = NOW() WHERE id = #{id}")
    int updateRetryStatus(@Param("id") Long id, @Param("status") int status,
                          @Param("retryCount") int retryCount,
                          @Param("nextRetryTime") java.time.LocalDateTime nextRetryTime);

    /** T-072：死信重投失败计数（负数表示死信重试次数，status 保持 3） */
    @Update("UPDATE t_local_message SET retry_count = #{retryCount}, updated_at = NOW() WHERE id = #{id}")
    int updateDeadRetry(@Param("id") Long id, @Param("retryCount") int retryCount);

    /** 根据事务ID查询（事务消息回查使用） */
    @Select("SELECT * FROM t_local_message WHERE transaction_id = #{transactionId} LIMIT 1")
    LocalMessage selectByTransactionId(@Param("transactionId") String transactionId);

    /** 根据事务ID + userId 查询（分库分表精确路由） */
    @Select("SELECT * FROM t_local_message WHERE transaction_id = #{transactionId} " +
            "AND user_id = #{userId} LIMIT 1")
    LocalMessage selectByTransactionIdAndUserId(@Param("transactionId") String transactionId,
                                                 @Param("userId") Long userId);
}
