package com.myxhs.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
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
    private final ApplicationContext applicationContext;
    private final ObjectProvider<BusinessMetrics> businessMetricsProvider;
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
     * 本服务的消费者组（启动时从 {@link RocketMQMessageListener} 动态发现）。
     * <p>
     * 原实现硬编码"全平台 26 组"清单，导致：① 新增消费者需手工同步，漏改=监控盲区；
     * ② 每个服务都为全部 26 组建 PullConsumer（15 服务 ≈390 个客户端）且同一组被 15 份冗余监控，
     * 告警 N 倍重复。改为只监控本服务实际拥有的组。
     * </p>
     */
    private final Set<String> consumerGroups = new LinkedHashSet<>();

    /** 上次采集值（用于把 DLQ 新增量计入 counter） */
    private final Map<String, Long> lastBacklog = new ConcurrentHashMap<>();

    /** 采集失败 WARN 去重（每组只打一次，避免刷日志） */
    private final Set<String> warnedGroups = ConcurrentHashMap.newKeySet();

    public DlqMetrics(MeterRegistry meterRegistry,
                      ApplicationContext applicationContext,
                      ObjectProvider<BusinessMetrics> businessMetricsProvider) {
        this.meterRegistry = meterRegistry;
        this.applicationContext = applicationContext;
        this.businessMetricsProvider = businessMetricsProvider;
    }

    @PostConstruct
    public void init() {
        discoverConsumerGroups();
        // 注册 Gauge：取值函数只读缓存（backlogCache），抓取时不再触发 RocketMQ 查询
        for (String consumerGroup : consumerGroups) {
            Gauge.builder("rocketmq.dlq.backlog", () -> backlogCache.getOrDefault(consumerGroup, -1L))
                    .description("DLQ 死信队列堆积量: " + consumerGroup)
                    .tag("consumer_group", consumerGroup)
                    .register(meterRegistry);
        }
        // 预注册 0 基线 counter：否则序列首次出现即为 1，increase() 无法算出首次增量（告警漏报）
        BusinessMetrics bm = businessMetricsProvider.getIfAvailable();
        if (bm != null) {
            for (String consumerGroup : consumerGroups) {
                bm.registerDlqCounter(consumerGroup, "%DLQ%" + consumerGroup);
            }
        }
        // 定时任务真正执行 RocketMQ 查询并缓存（30s），指标抓取全程无 I/O
        scheduler.scheduleAtFixedRate(this::collectDlqMetrics, 10, 30, TimeUnit.SECONDS);
        log.info("[DLQ监控] 已启动死信队列监控，本服务 {} 个 consumer group: {}，采集间隔 30s",
                consumerGroups.size(), consumerGroups);
    }

    /** 从本服务所有 @RocketMQMessageListener Bean 动态发现消费者组 */
    private void discoverConsumerGroups() {
        try {
            for (String beanName : applicationContext.getBeanNamesForAnnotation(RocketMQMessageListener.class)) {
                RocketMQMessageListener ann = applicationContext.findAnnotationOnBean(beanName, RocketMQMessageListener.class);
                if (ann != null && ann.consumerGroup() != null && !ann.consumerGroup().isEmpty()) {
                    consumerGroups.add(ann.consumerGroup());
                }
            }
        } catch (Exception e) {
            log.warn("[DLQ监控] 消费者组动态发现异常: {}", e.getMessage());
        }
    }

    private void collectDlqMetrics() {
        for (String consumerGroup : consumerGroups) {
            try {
                long backlog = getDlqBacklog(consumerGroup, "%DLQ%" + consumerGroup);
                long prev = lastBacklog.getOrDefault(consumerGroup, -1L);
                // 新增死信 → 计入 counter（DlqMessageDetected 告警依赖；首次采集不计算历史量）
                if (prev >= 0 && backlog > prev) {
                    BusinessMetrics bm = businessMetricsProvider.getIfAvailable();
                    if (bm != null) {
                        for (long i = prev; i < backlog; i++) {
                            bm.recordDlqMessage(consumerGroup, "%DLQ%" + consumerGroup);
                        }
                    }
                    log.warn("[DLQ监控] 检测到新死信: group={}, 新增={}条", consumerGroup, backlog - prev);
                }
                lastBacklog.put(consumerGroup, backlog);
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
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // topic 不存在 = 从未产生死信（或已清理），语义上积压为 0 而非未知。
            // 以 ResponseCode 判定为准（字符串在客户端版本间不稳定），字符串兜底。
            if (e instanceof MQClientException mce
                    && mce.getResponseCode() == org.apache.rocketmq.remoting.protocol.ResponseCode.TOPIC_NOT_EXIST) {
                return 0;
            }
            // 实测文案（RocketMQ 5.x 客户端，删 topic 后路由在但无队列）: "Can not find Message Queue for ..."
            if (msg.contains("Can not find Message Queue")
                    || msg.contains("route info") || msg.contains("TOPIC_NOT_EXIST") || msg.contains("not exist")) {
                return 0;
            }
            // 采集失败：默认 -1（未知），WARN 只打一次（原 debug 静默导致 -1 语义无从排查）
            if (warnedGroups.add(consumerGroup)) {
                log.warn("[DLQ监控] 采集积压失败(将按 -1 未知处理): topic={}, error={}", dlqTopic, msg);
            }
            return -1;
        }
    }
}
