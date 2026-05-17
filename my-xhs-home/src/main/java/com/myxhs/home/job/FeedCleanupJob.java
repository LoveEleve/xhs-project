package com.myxhs.home.job;

import com.myxhs.common.constants.RedisKeyConstants;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Feed 收件箱清理定时任务（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 3 点清理过期的收件箱数据（超过 7 天的笔记 ID）。
 * 防止收件箱 ZSet 无限膨胀导致 Redis 内存暴涨。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedCleanupJob {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${home.feed.inbox-max-days:7}")
    private int inboxMaxDays;

    /**
     * Feed 清理（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 3 * * ?（每天凌晨 3 点）
     */
    @XxlJob("feedCleanupJob")
    public void cleanup() {
        try {
            int cleaned = doCleanup();
            XxlJobHelper.handleSuccess("Feed清理完成，清理 " + cleaned + " 条过期数据");
        } catch (Exception e) {
            log.error("[Feed清理] 执行异常", e);
            XxlJobHelper.handleFail("Feed清理异常: " + e.getMessage());
        }
    }

    private int doCleanup() {
        long cutoffTime = System.currentTimeMillis() - (long) inboxMaxDays * 24 * 3600 * 1000;
        int cleaned = 0;

        // SCAN 遍历所有收件箱 Key（避免 KEYS * 阻塞 Redis）
        // 限速：每批处理后休眠 50ms，避免 SCAN + ZREMRANGEBYSCORE 持续占用 Redis CPU
        String pattern = RedisKeyConstants.FEED_INBOX + "*";
        try (Cursor<String> cursor = stringRedisTemplate.scan(ScanOptions.scanOptions()
                .match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long removed = stringRedisTemplate.opsForZSet()
                        .removeRangeByScore(key, 0, cutoffTime);
                if (removed != null && removed > 0) {
                    cleaned += removed.intValue();
                }
                // 限速：每处理一个 Key 后休眠 50ms，防止 Redis CPU 飙高
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Feed清理] 被中断");
        }

        // 同样清理发件箱
        String outboxPattern = RedisKeyConstants.FEED_OUTBOX + "*";
        try (Cursor<String> cursor = stringRedisTemplate.scan(ScanOptions.scanOptions()
                .match(outboxPattern).count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long removed = stringRedisTemplate.opsForZSet()
                        .removeRangeByScore(key, 0, cutoffTime);
                if (removed != null && removed > 0) {
                    cleaned += removed.intValue();
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Feed清理] 被中断");
        }

        if (cleaned > 0) {
            log.info("[Feed清理] 完成: 清理{}条过期数据, 截止时间={}", cleaned, cutoffTime);
        }

        return cleaned;
    }
}
