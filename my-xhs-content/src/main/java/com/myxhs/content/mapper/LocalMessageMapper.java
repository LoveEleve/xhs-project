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

    /** 标记为已发送 */
    @Update("UPDATE t_local_message SET status = 1 WHERE id = #{id}")
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
}
