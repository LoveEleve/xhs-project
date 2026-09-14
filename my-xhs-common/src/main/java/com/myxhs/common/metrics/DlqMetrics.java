package com.myxhs.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DlqMetrics {

    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dlq-monitor");
        t.setDaemon(true);
        return t;
    });

    @Value("${rocketmq.name-server:21.91.124.110:9876}")
    private String nameServer;

    private final Map<String, DefaultMQPullConsumer> consumers = new ConcurrentHashMap<>();

    /** DLQ 积压缓存（定时任务计算，Gauge 只读此值，避免抓取时执行 RocketMQ I/O） */
    private final Map<String, Long> backlogCache = new ConcurrentHashMap<>();

    /**
     * 所有配置了 maxReconsumeTimes 的 consumer group 列表。
     * 重试耗尽后消息进入 %DLQ%<consumerGroup>，需要监控其堆积量。
     */
    private static final String[] CONSUMER_GROUPS = {
            // order
            "order-close-consumer-group",
            "order-compensation-consumer-group",
            "order-pay-result-consumer-group",
            "order-refund-result-consumer-group",
            // payment
            "payment-pay-result-consumer-group",
            "payment-refund-result-consumer-group",
            // inventory
            "inventory-deduct-consumer-group",
            "inventory-order-transaction-consumer-group",
            "inventory-cache-evict-consumer-group",
            // coupon
            "coupon-claim-consumer-group",
            "coupon-return-redis-repair-consumer-group",
            // cart
            "cart-sync-consumer-group",
            "cart-event-sink-group",
            // search / sync
            "note-index-sync-consumer-group",
            "product-index-sync-consumer-group",
            "note-delete-consumer-group",
            "counter-es-sync-consumer-group",
            // home / feed
            "feed-push-consumer-group",
            // notification
            "notification-event-consumer-group",
            // user
            "user-cache-evict-consumer-group",
            // analytics
            "like-unlike-consumer-group",
            "favorite-unlike-consumer-group",
            "follow-consumer-group",
            "unfollow-consumer-group",
            // counter
            "counter-consumer-group",
            // recommend / behavior
            "recommend-behavior-consumer-group"
            // 维护约定：与各服务 @RocketMQMessageListener(maxReconsumeTimes>0) 的 consumerGroup 保持一致；
            // 新增消费者时必须同步此列表，否则该组 DLQ 无监控（RV11 cart 死信盲区的同类问题）
    };

    public DlqMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // 注册 Gauge：取值函数只读缓存（backlogCache），抓取时不再触发 RocketMQ 查询
        for (String consumerGroup : CONSUMER_GROUPS) {
            Gauge.builder("rocketmq.dlq.backlog", () -> backlogCache.getOrDefault(consumerGroup, -1L))
                    .description("DLQ 死信队列堆积量: " + consumerGroup)
                    .tag("consumer_group", consumerGroup)
                    .register(meterRegistry);
        }
        // 定时任务真正执行 RocketMQ 查询并缓存（30s），指标抓取全程无 I/O
        scheduler.scheduleAtFixedRate(this::collectDlqMetrics, 10, 30, TimeUnit.SECONDS);
        log.info("[DLQ监控] 已启动死信队列监控，监控 {} 个 consumer group，采集间隔 30s", CONSUMER_GROUPS.length);
    }

    private void collectDlqMetrics() {
        for (String consumerGroup : CONSUMER_GROUPS) {
            try {
                long backlog = getDlqBacklog(consumerGroup, "%DLQ%" + consumerGroup);
                backlogCache.put(consumerGroup, backlog);
            } catch (Exception e) {
                backlogCache.put(consumerGroup, -1L);
                log.debug("[DLQ监控] 采集积压异常: {}", consumerGroup, e);
            }
        }
    }

    private long getDlqBacklog(String consumerGroup, String dlqTopic) {
        try {
            DefaultMQPullConsumer consumer = consumers.computeIfAbsent(consumerGroup, k -> {
                DefaultMQPullConsumer c = new DefaultMQPullConsumer("DLQ_MONITOR_" + k);
                try {
                    c.setNamesrvAddr(nameServer);
                    c.start();
                    log.info("[DLQ监控] 创建 PullConsumer: DLQ_MONITOR_{}", k);
                } catch (MQClientException e) {
                    log.error("[DLQ监控] 启动 PullConsumer 失败: {}", k, e);
                    return null;
                }
                return c;
            });

            if (consumer == null) return -1;

            Set<MessageQueue> queues = consumer.fetchSubscribeMessageQueues(dlqTopic);
            long totalBacklog = 0;
            for (MessageQueue queue : queues) {
                // DLQ 无消费者组处理，堆积量 = 队列中现有消息数（max - min）
                // 原实现用 searchOffset(now) 与 maxOffset 相减，两者恒等 → 指标永远为 0（监控盲区）
                long minOffset = consumer.minOffset(queue);
                long maxOffset = consumer.maxOffset(queue);
                if (maxOffset > minOffset) {
                    totalBacklog += (maxOffset - minOffset);
                }
            }
            return totalBacklog;
        } catch (Exception e) {
            log.debug("[DLQ监控] 获取堆积量异常: topic={}, error={}", dlqTopic, e.getMessage());
            return -1;
        }
    }
}
