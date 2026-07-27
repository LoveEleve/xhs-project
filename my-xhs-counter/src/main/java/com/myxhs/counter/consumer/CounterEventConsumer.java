package com.myxhs.counter.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.counter.service.CounterService;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 计数事件 MQ 消费者 — 消费 SOCIAL_TOPIC 更新计数
 * <p>
 * 消费 SOCIAL_TOPIC 的 LIKE/UNLIKE/FAVORITE/UNFAVORITE Tag，
 * 根据事件类型映射到对应的计数维度，执行 Redis 原子去重 + INCR/DECR + Buffer 写入。
 * <p>
 * 消息来源：
 * - analytics 服务：点赞/取消点赞/收藏/取消收藏事件 → 计数更新
 * - content 服务（后续）：评论事件 → 评论数更新
 * <p>
 * 事件到计数的映射规则：
 * - LIKE      → 笔计点赞数 +1（bizType=1笔记时 targetType=1, countType=1）
 * - UNLIKE    → 笔计点赞数 -1
 * - FAVORITE  → 笔记收藏数 +1（targetType=1, countType=2）
 * - UNFAVORITE→ 笔记收藏数 -1
 * <p>
 * 幂等性（生产级修复 m3）：
 * - 使用 Lua 脚本原子化「msgId 去重 + 计数增减」，消除竞态窗口
 * - 重复消息被去重拦截（status=0），Consumer 静默跳过并 ACK
 * - 归零保护消息正常 ACK（status=-1），避免无限重试
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "LIKE||UNLIKE||FAVORITE||UNFAVORITE",
        consumerGroup = "counter-consumer-group",
        maxReconsumeTimes = 3
)
public class CounterEventConsumer implements RocketMQListener<MessageExt> {

    private final CounterService counterService;
    private final ObjectMapper objectMapper;

    /** 计数类型常量：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注 */
    private static final int COUNT_TYPE_LIKE = 1;
    private static final int COUNT_TYPE_FAVORITE = 2;

    /** 目标类型常量：1-笔记 2-用户 */
    private static final int TARGET_TYPE_NOTE = 1;
    private static final int TARGET_TYPE_USER = 2;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceContext(msg);
        String msgId = msg.getMsgId();
        try {
            String tag = msg.getTags();
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);

            log.debug("[计数Consumer] 收到消息: tag={}, msgId={}", tag, msgId);

            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = objectMapper.readValue(message, Map.class);

            switch (tag) {
                case "LIKE":
                case "UNLIKE":
                    handleLikeEvent(msgId, eventMap, tag);
                    break;
                case "FAVORITE":
                case "UNFAVORITE":
                    handleFavoriteEvent(msgId, eventMap, tag);
                    break;
                default:
                    log.warn("[计数Consumer] 未知Tag: {}, 忽略消息", tag);
            }
        } catch (Exception e) {
            log.error("[计数Consumer] 消费失败: msgId={}", msgId, e);
            throw new RuntimeException("计数消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceContext();
        }
    }

    /**
     * 处理点赞/取消点赞事件
     * <p>
     * LikeEvent 字段：userId, bizType(1-笔记 2-评论), bizId, action(LIKE/UNLIKE)
     * 当 bizType=1(笔记) 时，更新笔记的点赞计数。
     * 使用 incrementWithDedup/decrementWithDedup 保证 MQ 幂等。
     * </p>
     */
    private void handleLikeEvent(String msgId, Map<String, Object> eventMap, String tag) {
        Integer bizType = toInt(eventMap.get("bizType"));
        Long bizId = toLong(eventMap.get("bizId"));

        if (bizType == null || bizId == null) {
            log.warn("[计数Consumer] 点赞事件缺少必填字段: bizType={}, bizId={}", bizType, bizId);
            return;
        }

        if (bizType == 1) {
            int targetType = TARGET_TYPE_NOTE;
            int countType = COUNT_TYPE_LIKE;

            boolean executed;
            if ("LIKE".equals(tag)) {
                executed = counterService.incrementWithDedup(msgId, targetType, bizId, countType);
            } else {
                executed = counterService.decrementWithDedup(msgId, targetType, bizId, countType);
            }

            log.info("[计数Consumer] 点赞计数{}: msgId={}, targetType={}, targetId={}, countType={}, action={}",
                    executed ? "更新" : "去重跳过", msgId, targetType, bizId, countType, tag);
        }
    }

    /**
     * 处理收藏/取消收藏事件
     * <p>
     * FavoriteEvent 字段：userId, noteId, action(FAVORITE/UNFAVORITE), timestamp
     * 收藏只针对笔记，targetType=1, countType=2(收藏)
     * 使用 incrementWithDedup/decrementWithDedup 保证 MQ 幂等。
     * </p>
     */
    private void handleFavoriteEvent(String msgId, Map<String, Object> eventMap, String tag) {
        Long noteId = toLong(eventMap.get("noteId"));

        if (noteId == null) {
            log.warn("[计数Consumer] 收藏事件缺少必填字段: noteId={}", noteId);
            return;
        }

        int targetType = TARGET_TYPE_NOTE;
        int countType = COUNT_TYPE_FAVORITE;

        boolean executed;
        if ("FAVORITE".equals(tag)) {
            executed = counterService.incrementWithDedup(msgId, targetType, noteId, countType);
        } else {
            executed = counterService.decrementWithDedup(msgId, targetType, noteId, countType);
        }

        log.info("[计数Consumer] 收藏计数{}: msgId={}, targetType={}, targetId={}, countType={}, action={}",
                executed ? "更新" : "去重跳过", msgId, targetType, noteId, countType, tag);
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
