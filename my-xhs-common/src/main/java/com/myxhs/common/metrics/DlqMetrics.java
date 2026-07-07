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

    /**
     * 所有配置了 maxReconsumeTimes 的 consumer group 列表。
     * 重试耗尽后消息进入 %DLQ%<consumerGroup>，需要监控其堆积量。
     */
    private static final String[] CONSUMER_GROUPS = {
            // order
            "order-close-consumer-group",
            "order-compensation-consumer-group",
            // payment
            "payment-pay-result-consumer-group",
            "payment-refund-result-consumer-group",
            // inventory
            "inventory-deduct-consumer-group",
            "inventory-order-transaction-consumer-group",
            "inventory-cache-evict-consumer-group",
            // coupon
            "coupon-claim-consumer-group",
            // cart
            "cart-sync-consumer-group",
            // search / sync
            "note-index-sync-consumer-group",
            "product-index-sync-consumer-group",
            // home / feed
            "feed-push-consumer-group",
            // notification
            "notification-event-consumer-group",
            // user
            "user-cache-evict-consumer-group",
            // product (broadcast)
            "product-cache-evict-group",
            // analytics
            "like-consumer-group",
            "unlike-consumer-group",
            "favorite-consumer-group",
            "unfavorite-consumer-group",
            "follow-consumer-group",
            "unfollow-consumer-group",
            // counter
            "counter-consumer-group",
            // recommend / behavior
            "recommend-behavior-consumer-group"
    };

    public DlqMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::collectDlqMetrics, 10, 30, TimeUnit.SECONDS);
        log.info("[DLQ监控] 已启动死信队列监控，监控 {} 个 consumer group，采集间隔 30s", CONSUMER_GROUPS.length);
    }

    private void collectDlqMetrics() {
        for (String consumerGroup : CONSUMER_GROUPS) {
            try {
                String dlqTopic = "%DLQ%" + consumerGroup;
                Gauge.builder("rocketmq.dlq.backlog", () -> getDlqBacklog(consumerGroup, dlqTopic))
                        .description("DLQ 死信队列堆积量: " + consumerGroup)
                        .tag("consumer_group", consumerGroup)
                        .register(meterRegistry);
            } catch (Exception e) {
                log.debug("[DLQ监控] 注册 Gauge 失败: {}", consumerGroup, e);
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
                long offset = consumer.searchOffset(queue, System.currentTimeMillis());
                long maxOffset = consumer.maxOffset(queue);
                if (maxOffset > offset) {
                    totalBacklog += (maxOffset - offset);
                }
            }
            return totalBacklog;
        } catch (Exception e) {
            log.debug("[DLQ监控] 获取堆积量异常: topic={}, error={}", dlqTopic, e.getMessage());
            return -1;
        }
    }
}
