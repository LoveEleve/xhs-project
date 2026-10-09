package com.myxhs.product.consumer;

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
 * 缓存失效兜底消费者（P2/2026-09-27）
 * <p>
 * 消费 {@link CacheEvictMessage#TOPIC}：延迟双删失败/Redis 不可用时由 CacheHelper 或本服务发出的
 * 兜底消息，在 Redis 恢复后完成删除（删除天然幂等，重复消费无副作用）。
 * </p>
 * <p>
 * 每个使用兜底的服各自注册独立消费组（集群模式下各组均收到全量消息），避免此前
 * "只有 user 一个消费组"的单点依赖。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = CacheEvictMessage.TOPIC,
        consumerGroup = "product-cache-evict-consumer-group",
        maxReconsumeTimes = 3
)
public class CacheEvictConsumer implements RocketMQListener<MessageExt> {

    private final RedisOperator redisOperator;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            String cacheKey;

            // 兼容两种格式：CacheHelper 发纯 key，未来可升级为 JSON
            if (body.startsWith("{")) {
                CacheEvictMessage evictMsg = JSON.parseObject(body, CacheEvictMessage.class);
                cacheKey = evictMsg != null ? evictMsg.getKey() : null;
            } else {
                cacheKey = body;
            }

            if (cacheKey == null || cacheKey.isEmpty()) {
                log.warn("[缓存兜底] 消息格式异常，跳过: mqMsgId={}", msg.getMsgId());
                return;
            }

            redisOperator.delete(cacheKey);
            log.info("[缓存兜底] 删除成功: key={}, retryCount={}", cacheKey, msg.getReconsumeTimes());

        } catch (Exception e) {
            // 抛出异常触发 RocketMQ 重试
            log.error("[缓存兜底] 删除失败，等待MQ重试: mqMsgId={}", msg.getMsgId(), e);
            throw new RuntimeException("缓存删除失败，触发MQ重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
