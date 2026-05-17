package com.myxhs.common.id;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * ID 生成工具类（三合一：雪花 + 号段 + Redis自增）
 * <p>
 * 根据不同业务场景选择合适的 ID 生成策略：
 * - 雪花 ID：订单/笔记/评论等（18 位，趋势递增，本地生成极高性能）
 * - 号段模式：用户 ID（8-10 位短 ID，适合 URL 展示）
 * - Redis 自增：流水号（日期前缀 + 6 位序号）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdGeneratorUtil {

    private final SegmentIdGenerator segmentIdGenerator;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 雪花 ID（订单/笔记/评论等）
     * <p>
     * 使用 MyBatis-Plus 内置的雪花算法（基于 Snowflake），
     * 无需额外配置 WorkerId（MP 自动处理）。
     * 如果需要更精细的控制，可以替换为 CosId。
     * </p>
     */
    public long nextId() {
        // 使用 MyBatis-Plus 内置的 IdWorker
        return com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
    }

    /**
     * 号段模式 ID（用户 ID，短 ID）
     *
     * @param bizTag 业务标签（如 "user"、"order"）
     * @return 号段 ID
     */
    public long nextSegmentId(String bizTag) {
        return segmentIdGenerator.nextId(bizTag);
    }

    /**
     * Redis 自增 ID（流水号，日期前缀）
     * <p>
     * 格式：{prefix}{yyyyMMdd}{6位序号}
     * 例：PAY20260512000001
     * </p>
     *
     * @param prefix 前缀（如 "PAY"、"ORD"）
     * @return 流水号
     */
    public String nextSerialNo(String prefix) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "id:serial:" + prefix + ":" + dateStr;
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        // 只在首次创建 Key 时设置过期（seq==1 说明是新 Key），避免每次调用都 expire 的多余网络往返
        if (seq != null && seq == 1) {
            stringRedisTemplate.expire(key, 2, TimeUnit.DAYS);
        }
        return prefix + dateStr + String.format("%06d", seq);
    }
}
