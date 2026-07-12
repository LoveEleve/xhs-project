package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.FollowEvent;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 取消关注事件 MQ 消费者 — 异步删除 MySQL 记录
 * <p>
 * 消费 SOCIAL_TOPIC:UNFOLLOW 消息，从 t_follow 表删除关注记录。
 * 删除操作天然幂等（DELETE 不存在的记录不会报错）。
 * </p>
 * <p>
 * 注意：当前 FollowService 使用同步方式，不发送 MQ 消息，
 * 此消费者暂不会收到消息。默认禁用，如需启用请设置
 * myxhs.mq.unfollow-consumer.enabled=true。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "myxhs.mq.unfollow-consumer.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "UNFOLLOW",
        consumerGroup = "unfollow-consumer-group",
        maxReconsumeTimes = 3
)
public class UnfollowConsumer implements RocketMQListener<MessageExt> {

    private final FollowMapper followMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            FollowEvent event = objectMapper.readValue(message, FollowEvent.class);

            // 删除 MySQL 记录（DELETE 天然幂等）
            int deleted = followMapper.deleteByUserIdAndFollowUserId(
                    event.getUserId(), event.getTargetUserId());
            log.info("[取消关注Consumer] 删除记录: userId={}, targetUserId={}, deleted={}",
                    event.getUserId(), event.getTargetUserId(), deleted);
        } catch (Exception e) {
            log.error("[取消关注Consumer] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            throw new RuntimeException("取消关注消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
