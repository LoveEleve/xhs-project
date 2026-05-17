package com.myxhs.user.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.cache.CacheEvictMessage;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 缓存删除兜底消费者
 * <p>
 * 消费 CACHE_EVICT_TOPIC 消息，异步重试删除缓存。
 * 这是缓存一致性三重保障的 L3 层（MQ 兜底）。
 * </p>
 * <p>
 * 触发时机：CacheHelper.deleteAfterUpdate() 重试 3 次仍失败时，
 * 由调用方发送 MQ 消息到此 Topic。
 * </p>
 * <p>
 * 重试策略：RocketMQ 内置重试机制（最多 16 次，指数退避），
 * 超过重试次数进死信队列，运维人工介入。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = CacheEvictMessage.TOPIC,
        consumerGroup = "user-cache-evict-consumer-group",
        selectorExpression = "user-service"
)
public class CacheEvictConsumer implements RocketMQListener<MessageExt> {

    private final RedisOperator redisOperator;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            CacheEvictMessage evictMsg = JSON.parseObject(body, CacheEvictMessage.class);

            if (evictMsg == null || evictMsg.getKey() == null) {
                log.warn("[缓存兜底] 消息格式异常，跳过: mqMsgId={}", msg.getMsgId());
                return;
            }

            // 删除缓存
            redisOperator.delete(evictMsg.getKey());
            log.info("[缓存兜底] 删除成功: key={}, source={}, retryCount={}",
                    evictMsg.getKey(), evictMsg.getSource(), evictMsg.getRetryCount());

        } catch (Exception e) {
            // 抛出异常触发 RocketMQ 重试（指数退避，最多 16 次）
            log.error("[缓存兜底] 删除失败，等待MQ重试: mqMsgId={}", msg.getMsgId(), e);
            throw new RuntimeException("缓存删除失败，触发MQ重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
