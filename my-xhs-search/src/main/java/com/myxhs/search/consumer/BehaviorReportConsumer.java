package com.myxhs.search.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 推荐行为上报消费者
 * <p>
 * 消费 RECOMMEND_BEHAVIOR_TOPIC 中的行为事件，批量写入 MySQL。
 * 从 MQ 异步写入的好处：
 * 1. 削峰填谷：行为上报 QPS 可能上万，MQ 缓冲后平滑写入 DB
 * 2. 批量插入：消费者可以攒批后一次 INSERT，比逐条插入快 5~10 倍
 * 3. 降级兜底：MQ 发送失败时，生产端降级为同步写 DB
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "RECOMMEND_BEHAVIOR_TOPIC",
        consumerGroup = "recommend-behavior-consumer-group",
        maxReconsumeTimes = 3
)
public class BehaviorReportConsumer implements RocketMQListener<MessageExt> {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> event = JSON.parseObject(body, Map.class);

            Long id = toLong(event.get("id"));
            Long userId = toLong(event.get("userId"));
            Long noteId = toLong(event.get("noteId"));
            Integer behaviorType = toInt(event.get("behaviorType"));
            Integer duration = toInt(event.get("duration"));

            if (userId == null || noteId == null || behaviorType == null) {
                log.warn("[推荐行为] 消息格式异常，跳过: msgId={}", msg.getMsgId());
                return;
            }

            jdbcTemplate.update(
                    "INSERT INTO t_user_behavior (id, user_id, note_id, behavior_type, duration) VALUES (?, ?, ?, ?, ?)",
                    id != null ? id : 0L,
                    userId,
                    noteId,
                    behaviorType,
                    duration != null ? duration : 0);

            log.debug("[推荐行为] 写入DB成功: userId={}, noteId={}, type={}",
                    userId, noteId, behaviorType);

        } catch (Exception e) {
            log.error("[推荐行为] 消费异常: mqMsgId={}", msg.getMsgId(), e);
            // 不抛异常，避免无限重试。行为数据允许少量丢失。
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return null; }
    }
}
