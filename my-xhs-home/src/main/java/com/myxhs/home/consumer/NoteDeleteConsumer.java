package com.myxhs.home.consumer;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 笔记删除事件消费者 — 清理作者发件箱, 防止已删笔记通过 outbox 拉取
 * <p>
 * 监听 SOCIAL_TOPIC:NOTE_DELETE, 清理 myxhs:feed:outbox:{authorId} ZSet
 * 粉丝 inbox 残留靠 7 天 TTL 自然过期 + FeedCleanupJob 辅助清理
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "NOTE_DELETE",
        consumerGroup = "note-delete-consumer-group",
        maxReconsumeTimes = 3
)
public class NoteDeleteConsumer implements RocketMQListener<MessageExt> {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            // 解析 authorId 和 noteId (从 body 或 property 获取)
            String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            var event = com.alibaba.fastjson2.JSON.parseObject(body, NoteDeleteEvent.class);
            if (event == null || event.getNoteId() == null || event.getUserId() == null) {
                log.warn("[NoteDelete] 消息体解析失败: msgId={}", msg.getMsgId());
                return;
            }

            Long noteId = event.getNoteId();
            Long authorId = event.getUserId();

            // 清理作者发件箱: 防止新关注者通过 outbox 拉取已删笔记
            String outboxKey = RedisKeyConstants.FEED_OUTBOX + authorId;
            Long removed = stringRedisTemplate.opsForZSet().remove(outboxKey, String.valueOf(noteId));
            // T-127（2026-08-16）：同步清理推荐关注召回源 following:latest——
            // 修复前 NOTE_DELETE 只清 outbox，FOLLOWING 召回（recommend:following:latest:{followerId}）
            // 残留已删笔记（推模式按粉丝维度写入，作者侧无法直接定位）→ 推荐中出现已删 noteId。
            // following:latest 按 followerId 分 key，删除笔记（低频操作）时 SCAN 全量清除该 noteId。
            int followingCleaned = removeFromFollowingLatest(noteId);
            // T-126（2026-08-16）：设置短 TTL 已删标记，防 FEED_TOPIC 推送消息晚到/重投把已删笔记写回 outbox——
            // 修复前：NOTE_DELETE 清 outbox 后，重复投递的推送消息（MQ 重试/补偿重投）再次 ZADD 已删笔记
            // → 大V outbox 残留已删笔记（实测 15:55/15:56 两次消费同 noteId 重新入 outbox）
            // TTL 从 5min 提到 7 天：5min 之后晚到的重投仍能把已删笔记写回 outbox（实测过漏网），
            // 7 天与 MQ/补偿窗口对齐（仅每个被删笔记一个小 key）
            stringRedisTemplate.opsForValue().set(
                    "myxhs:note:deleted:" + noteId, "1", java.time.Duration.ofDays(7));
            log.info("[NoteDelete] 清理完成: authorId={}, noteId={}, outboxRemoved={}, followingCleaned={}",
                    authorId, noteId, removed, followingCleaned);

        } catch (Exception e) {
            log.error("[NoteDelete] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("NoteDelete消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * T-127（2026-08-16）：SCAN 全量清理 following:latest 中的已删笔记
     * <p>
     * following:latest:{followerId} 按粉丝维度分 key（FeedPushConsumer 推模式写入），
     * 删除事件只有 authorId 无法直接定位——笔记删除为低频操作，SCAN 全量清除可接受
     * （与 FeedCleanupJob 的 SCAN 风格一致）。
     * </p>
     */
    private int removeFromFollowingLatest(Long noteId) {
        int cleaned = 0;
        try (var cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(RedisKeyConstants.RECOMMEND_FOLLOWING_LATEST + "*")
                        .count(500).build())) {
            int scanned = 0;
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long r = stringRedisTemplate.opsForZSet().remove(key, String.valueOf(noteId));
                if (r != null && r > 0) {
                    cleaned++;
                }
                // 限速：key 多时避免长占 Redis 单线程（每 500 个 key 让出 10ms）
                if (++scanned % 500 == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[NoteDelete] following:latest 清理异常(部分残留靠 TTL 回收): noteId={}, err={}",
                    noteId, e.getMessage());
        }
        return cleaned;
    }

    /**
     * 笔记删除事件（从 SOCIAL_TOPIC:NOTE_DELETE 消息解析）
     */
    @lombok.Data
    private static class NoteDeleteEvent {
        private Long noteId;
        private Long userId;
    }
}
