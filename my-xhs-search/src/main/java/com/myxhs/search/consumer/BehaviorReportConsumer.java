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

            if (id == null || userId == null || noteId == null || behaviorType == null) {
                throw new IllegalArgumentException("行为消息缺少必填字段");
            }

            // INSERT IGNORE：MQ 重投（同一 eventId）幂等跳过，避免主键冲突被当"毒丸"送进 DLQ
            int affected = jdbcTemplate.update(
                    "INSERT IGNORE INTO t_user_behavior (id, user_id, note_id, behavior_type, duration) VALUES (?, ?, ?, ?, ?)",
                    id,
                    userId,
                    noteId,
                    behaviorType,
                    duration != null ? duration : 0);
            if (affected == 0) {
                log.debug("[推荐行为] 重复投递已忽略: eventId={}, userId={}, noteId={}", id, userId, noteId);
                return;
            }

            log.debug("[推荐行为] 写入DB成功: userId={}, noteId={}, type={}",
                    userId, noteId, behaviorType);

        } catch (Exception e) {
            log.error("[推荐行为] 消费异常，触发 RocketMQ 重试: mqMsgId={}, reconsumeTimes={}",
                    msg.getMsgId(), msg.getReconsumeTimes(), e);
            throw new IllegalStateException("推荐行为写库失败", e);
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
