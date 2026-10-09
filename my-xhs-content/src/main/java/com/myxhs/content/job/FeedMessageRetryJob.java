package com.myxhs.content.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.entity.NotePublishEvent;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Feed 消息补发任务（定时扫描 + 重试）
 * <p>
 * 每 30 秒执行两类扫描：
 * </p>
 * <ul>
 *   <li><b>MQ 发送失败重试</b>：扫描 status=0（待发送）的消息，重新投递到 MQ。
 *       3 次重试后标记为死信（status=3）。</li>
 *   <li><b>Feed 推送未完成补偿</b>：扫描 status=1 AND push_status IN (0,1)（MQ 已发送但 Feed 推送未完成）
 *       的消息，重新投递到 MQ。FeedPushConsumer 从 Redis 断点恢复继续推送。</li>
 * </ul>
 * <p>
 * 多实例安全：Feed ZADD 天然幂等，重复推送不产生副作用。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedMessageRetryJob {

    private final LocalMessageMapper localMessageMapper;

    private final com.myxhs.common.metrics.BusinessMetrics businessMetrics;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String PUSH_PROGRESS_PREFIX = "myxhs:feed:push:progress:";

    /** 补偿重投上限：超过则置 push_status=3（终态）不再重投，避免死循环（此前无上限，60s 一轮无限重投）
     *  取值 100：60s 一轮约 1.7 小时，避免 MQ 短时抖动（10 分钟）就被误判终态造成"永不推送" */
    private static final int MAX_PUSH_COMPENSATE_RETRY = 100;
    /** 告警阈值：达到该次数开始 warn（便于运维发现长时间补偿） */
    private static final int PUSH_COMPENSATE_WARN_THRESHOLD = 10;
    private static final String PUSH_RETRY_PREFIX = "myxhs:feed:push:retry:";

    /** 分布式锁 Key — 防止多实例并发执行补偿任务 */
    private static final String RETRY_LOCK_KEY = "lock:feed:retry";
    private static final String COMPENSATE_LOCK_KEY = "lock:feed:compensate";

    private static final int RETRY_LOCK_TTL_SECONDS = 55;
    private static final int RETRY_DELAY_SECONDS = 60;
    private static final int MAX_RETRY = 3;
    private static final int SCAN_LIMIT = 500;
    private static final int PUSH_COMPENSATE_SCAN_LIMIT = 100;
    private static final int PUSH_COMPENSATE_DELAY_SECONDS = 120;

    @Scheduled(fixedRate = 30000)
    public void retryFailedMessages() {
        // 分布式锁：防止多实例并发执行
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(RETRY_LOCK_KEY, lockValue, Duration.ofSeconds(RETRY_LOCK_TTL_SECONDS));
        if (Boolean.FALSE.equals(locked)) return;

        try {
            int sent = 0;
            int failed = 0;

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(RETRY_DELAY_SECONDS);
            List<LocalMessage> messages = localMessageMapper.selectPending(cutoffTime, MAX_RETRY, SCAN_LIMIT);

            for (LocalMessage msg : messages) {
                final Long msgId = msg.getId();
                try {
                    NotePublishEvent event = objectMapper.readValue(msg.getBody(), NotePublishEvent.class);
                    // 补丁 localMsgId：body 序列化时 localMsgId 为 null，对齐 compensateIncompletePush
                    if (event.getLocalMsgId() == null) {
                        event.setLocalMsgId(msgId);
                    }
                    // asyncSend + 回调
                    rocketMQTemplate.asyncSend(msg.getTopic(), event,
                            new org.apache.rocketmq.client.producer.SendCallback() {
                                @Override
                                public void onSuccess(org.apache.rocketmq.client.producer.SendResult result) {
                                    localMessageMapper.markSent(msgId);
                                    log.info("[Feed补偿] 补发成功: localMsgId={}", msgId);
                                }
                                @Override
                                public void onException(Throwable e) {
                                    localMessageMapper.incrementRetry(msgId, MAX_RETRY);
                                    log.warn("[Feed补偿] 补发失败: localMsgId={}, retryCount={}", msgId, msg.getRetryCount() + 1);
                                }
                            });
                    sent++;
                } catch (Exception e) {
                    localMessageMapper.incrementRetry(msg.getId(), MAX_RETRY);
                    failed++;
                    log.warn("[Feed补偿] 补发异常: localMsgId={}", msgId, e);
                }
            }

            if (sent > 0 || failed > 0) {
                log.info("[Feed补偿] 执行完成: 成功={}, 失败={}, 扫描={}", sent, failed, messages.size());
            }

        } catch (Exception e) {
            log.error("[Feed补偿] 执行异常", e);
        } finally {
            // 安全释放锁：比对 value 防止误删其他实例的锁
            releaseLock(RETRY_LOCK_KEY, lockValue);
        }
    }

    /**
     * Feed 推送未完成补偿：扫描 MQ 已发送但 Feed 推送未完成的消息，重新投递到 MQ。
     * <p>
     * 触发条件：消息 status=1（MQ 已发送）且 push_status IN (0,1)（推送未完成或推送中），
     * 且距离创建时间超过 {@link #PUSH_COMPENSATE_DELAY_SECONDS}（避免刚发布就触发补偿）。
     * </p>
     * <p>
     * 断点续推机制：
     * FeedPushConsumer 每批 Pipeline 完成后将进度写入 Redis（key=myxhs:feed:push:progress:{localMsgId}），
     * MQ 重试时 Consumer 从 Redis 读取上次进度继续推送，不从头开始。
     * </p>
     */
    @Scheduled(fixedRate = 60000)
    public void compensateIncompletePush() {
        // 分布式锁：防止多实例并发执行补偿任务
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(COMPENSATE_LOCK_KEY, lockValue, Duration.ofSeconds(55));
        if (Boolean.FALSE.equals(locked)) return;

        try {
            int resent = 0;
            int failed = 0;
            int skipped = 0;

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(PUSH_COMPENSATE_DELAY_SECONDS);
            List<LocalMessage> messages = localMessageMapper.selectPendingPushWithDelay(
                    cutoffTime, PUSH_COMPENSATE_SCAN_LIMIT);

            for (LocalMessage msg : messages) {
                try {
                    // 重投次数上限（Redis 计数，TTL 24h）：超限置终态 3，交人工/告警，不再无脑重投
                    String retryKey = PUSH_RETRY_PREFIX + msg.getId();
                    Long attempts = stringRedisTemplate.opsForValue().increment(retryKey);
                    if (attempts != null && attempts == 1L) {
                        stringRedisTemplate.expire(retryKey, java.time.Duration.ofHours(24));
                    }
                    if (attempts != null && attempts == PUSH_COMPENSATE_WARN_THRESHOLD) {
                        log.warn("[Feed推送补偿] 已重投 {} 次(上限 {}): localMsgId={}",
                                attempts, MAX_PUSH_COMPENSATE_RETRY, msg.getId());
                    }
                    if (attempts != null && attempts > MAX_PUSH_COMPENSATE_RETRY) {
                        localMessageMapper.updatePushStatus(msg.getId(), 3);
                        businessMetrics.recordFeedPushTerminal("retry_exceeded");
                        log.error("[Feed推送补偿] 重投超过上限({}), 置终态(3)待人工: localMsgId={}",
                                MAX_PUSH_COMPENSATE_RETRY, msg.getId());
                        failed++;
                        continue;
                    }
                    // 1. 检查 Redis 推送进度——已完成则跳过
                    String progressKey = PUSH_PROGRESS_PREFIX + msg.getId();
                    Object status = stringRedisTemplate.opsForHash().get(progressKey, "status");
                    if ("completed".equals(status)) {
                        localMessageMapper.updatePushProgress(msg.getId(), 2, msg.getPushCursor() != null ? msg.getPushCursor() : 0);
                        skipped++;
                        continue;
                    }

                    // 2. Redis 不可用或 key 不存在时，MySQL 兜底——push_status=2 则跳过
                    if (status == null && msg.getPushStatus() != null && msg.getPushStatus() == 2) {
                        log.info("[Feed推送补偿] Redis缺失但MySQL已标记完成，跳过: localMsgId={}", msg.getId());
                        skipped++;
                        continue;
                    }

                    NotePublishEvent event = objectMapper.readValue(msg.getBody(), NotePublishEvent.class);
                    // 确保 localMsgId 已设置，FeedPushConsumer 需要它进行断点恢复
                    if (event.getLocalMsgId() == null) {
                        event.setLocalMsgId(msg.getId());
                    }
                    final Long currentMsgId = msg.getId();
                    rocketMQTemplate.asyncSend(msg.getTopic(), event,
                            new org.apache.rocketmq.client.producer.SendCallback() {
                                @Override
                                public void onSuccess(org.apache.rocketmq.client.producer.SendResult result) {
                                    localMessageMapper.updatePushStatus(currentMsgId, 1);
                                    log.info("[Feed推送补偿] 重新投递成功: localMsgId={}, noteId={}", currentMsgId, event.getNoteId());
                                }
                                @Override
                                public void onException(Throwable e) {
                                    log.warn("[Feed推送补偿] 重新投递失败: localMsgId={}", currentMsgId, e);
                                }
                            });
                    resent++;
                    // P3/2026-09-27：重投即安排"下次可推时间"（60s→600s 指数退避 + ±20% 抖动），
                    // 防止固定 60s 轮询对未完成条目反复重投；SQL 侧按 push_next_retry_time 过滤到期条目
                    int attempt = attempts == null ? 1 : (int) Math.min(attempts, 20);
                    long delaySeconds = com.myxhs.common.mq.RetryBackoffUtils.exponentialSeconds(attempt, 60, 600, 0.2);
                    localMessageMapper.updatePushNextRetry(msg.getId(), LocalDateTime.now().plusSeconds(delaySeconds));
                    log.info("[Feed推送补偿] 重新投递: localMsgId={}, noteId={}, nextRetry={}s",
                            msg.getId(), event.getNoteId(), delaySeconds);

                } catch (Exception e) {
                    failed++;
                    log.warn("[Feed推送补偿] 重新投递失败: localMsgId={}", msg.getId(), e);
                }
            }

            if (resent > 0 || failed > 0) {
                log.info("[Feed推送补偿] 执行完成: 重投={}, 失败={}, 扫描={}", resent, failed, messages.size());
            }

        } catch (Exception e) {
            log.error("[Feed推送补偿] 执行异常", e);
        } finally {
            releaseLock(COMPENSATE_LOCK_KEY, lockValue);
        }
    }

    /**
     * 安全释放分布式锁（Lua 原子比对 value，防止误删其他实例的锁）
     */
    private void releaseLock(String key, String expectedValue) {
        String luaScript = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        stringRedisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, Long.class),
                java.util.Collections.singletonList(key), expectedValue);
    }
}
