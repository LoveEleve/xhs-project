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
 * 每天凌晨 3 点执行两阶段清理：
 * 1. 过期数据清理：ZREMRANGEBYSCORE 删除超期笔记
 * 2.【M2新增】收件箱裁剪：ZCARD > maxSize 时 ZREMRANGEBYRANK 裁剪到上限
 * </p>
 * <p>
 * 裁剪逻辑从 FeedPushConsumer 的 Lua 脚本中分离至此，原因：
 * - 大部分收件箱远未达到上限，实时裁剪浪费 Redis 资源
 * - 裁剪不要求实时性，定时执行即可
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedCleanupJob {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${home.feed.inbox-max-days:7}")
    private int inboxMaxDays;

    @Value("${home.feed.inbox-max-size:500}")
    private int inboxMaxSize;

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

        String pattern = RedisKeyConstants.FEED_INBOX + "*";
        try (Cursor<String> cursor = stringRedisTemplate.scan(ScanOptions.scanOptions()
                .match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
            int count = 0;
                String key = cursor.next();

                // 1. 删除过期数据
                Long removed = stringRedisTemplate.opsForZSet()
                        .removeRangeByScore(key, 0, cutoffTime);
                if (removed != null && removed > 0) {
                    cleaned += removed.intValue();
                }

                // 2.【M2】裁剪超量数据：ZCARD > maxSize 时裁剪到 maxSize
                Long card = stringRedisTemplate.opsForZSet().zCard(key);
                if (card != null && card > inboxMaxSize) {
                    stringRedisTemplate.opsForZSet()
                            .removeRange(key, 0, card - inboxMaxSize - 1);
                    cleaned += (int) (card - inboxMaxSize);
                }

                if (++count % 100 == 0) Thread.sleep(50);
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
            int count = 0;
                String key = cursor.next();
                Long removed = stringRedisTemplate.opsForZSet()
                        .removeRangeByScore(key, 0, cutoffTime);
                if (removed != null && removed > 0) {
                    cleaned += removed.intValue();
                }
                if (++count % 100 == 0) Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Feed清理] 被中断");
        }

        if (cleaned > 0) {
            log.info("[Feed清理] 完成: 清理{}条数据, 截止时间={}", cleaned, cutoffTime);
        }

        return cleaned;
    }
}
