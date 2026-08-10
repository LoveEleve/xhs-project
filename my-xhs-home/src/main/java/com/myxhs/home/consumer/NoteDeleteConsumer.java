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
            log.info("[NoteDelete] 清理完成: authorId={}, noteId={}, outboxRemoved={}",
                    authorId, noteId, removed);

        } catch (Exception e) {
            log.error("[NoteDelete] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("NoteDelete消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
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
