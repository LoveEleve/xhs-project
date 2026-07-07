package com.myxhs.product.listener;

import com.myxhs.product.service.SpuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 商品缓存清除 MQ 监听器
 * <p>
 * 【解决的问题】
 * Caffeine 是 JVM 级别缓存，多实例之间不共享。
 * 商品变更后，只有处理请求的实例清了自己的 Caffeine，其他实例还缓存着旧值。
 * <p>
 * 【解决方案】
 * 商品变更时发 MQ 广播消息，所有实例收到后清除本地 Caffeine。
 * 即使 MQ 丢消息，Caffeine TTL 5 分钟兜底。
 * <p>
 * 【注意】
 * consumerGroup 使用广播模式（messageModel = BROADCASTING），
 * 确保每个实例都能收到消息。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = SpuService.CACHE_EVICT_TOPIC,
        consumerGroup = "product-cache-evict-group",
        // 广播模式：每个消费者实例都收到消息
        messageModel = org.apache.rocketmq.spring.annotation.MessageModel.BROADCASTING,
        maxReconsumeTimes = 3
)
public class CacheEvictListener implements RocketMQListener<Long> {

    private final SpuService spuService;

    @Override
    public void onMessage(Long spuId) {
        log.info("[MQ] 收到缓存清除广播, spuId={}", spuId);
        spuService.evictLocalCache(spuId);
    }
}
