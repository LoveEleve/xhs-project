package com.myxhs.order.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.service.OrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地消息表补发定时任务（XXL-Job 分布式调度）
 * <p>
 * 扫描 status=0（待处理）或 status=2（失败）且到达重试时间的消息，重新发送到 MQ。
 * 指数退避重试：30s, 60s, 120s, 240s, 480s（最多 5 次），耗尽后标记为死信（status=3）。
 * </p>
 * <p>
 * 本地消息表的价值：
 * 即使 MQ Broker 整体宕机，本地消息表和订单表在同一个 MySQL 事务中，
 * 只要 MySQL 不挂消息就不会丢。Broker 恢复后定时任务扫描补发。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageRetryJob {

    private final LocalMessageMapper localMessageMapper;
    private final RocketMQTemplate rocketMQTemplate;

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 5;

    /** 死信累计数（Prometheus 可通过 JMX/Micrometer 暴露） */
    private final AtomicLong deadLetterCount = new AtomicLong(0);

    /** 获取死信累计数（供健康检查/告警使用） */
    public long getDeadLetterCount() {
        return deadLetterCount.get();
    }

    /**
     * 本地消息补发（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0/30 * * * * ?（每 30 秒）
     * <p>
     * 消费端必须保证幂等（因为事务消息可能已经 Commit 成功，消费者已消费过一次）。
     * 本地消息表补发是"至少一次"语义，不是"恰好一次"。
     * </p>
     */
    @XxlJob("localMessageRetryJob")
    public void retryMessages() {
        try {
            doRetry();
            XxlJobHelper.handleSuccess("本地消息补发完成");
        } catch (Exception e) {
            log.error("[本地消息] 补发异常", e);
            XxlJobHelper.handleFail("本地消息补发异常: " + e.getMessage());
        }
    }

    /**
     * 补发逻辑：扫描到达重试时间的待处理消息 → 重新发送到 MQ → 更新状态
     */
    private void doRetry() {
        // 只扫描到达重试时间的消息
        List<LocalMessage> messages = localMessageMapper.selectList(
            new LambdaQueryWrapper<LocalMessage>()
                .in(LocalMessage::getStatus, 0, 2)
                .le(LocalMessage::getNextRetryTime, LocalDateTime.now())
                .last("LIMIT " + BATCH_SIZE)
        );

        if (messages.isEmpty()) {
            return;
        }

        int success = 0;
        int failed = 0;
        for (LocalMessage msg : messages) {
            try {
                String topic = resolveTopic(msg.getOperationType());

                SendResult result = rocketMQTemplate.syncSend(topic,
                        MessageBuilder.withPayload(msg.getPayload())
                                .setHeader("orderNo", msg.getTransactionId())
                                .setHeader("retryFromLocalMessage", "true")
                                .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS,
                                        msg.getTransactionId())
                                .build());

                if (result.getSendStatus() == SendStatus.SEND_OK) {
                    localMessageMapper.markSuccess(msg.getId());
                    success++;
                } else {
                    handleRetryFailure(msg);
                    failed++;
                }
            } catch (Exception e) {
                log.warn("[本地消息] 补发失败: id={}, transactionId={}, error={}",
                        msg.getId(), msg.getTransactionId(), e.getMessage());
                handleRetryFailure(msg);
                failed++;
            }
        }

        if (success > 0 || failed > 0) {
            log.info("[本地消息] 补发完成: 成功={}, 失败={}", success, failed);
        }
    }

    /**
     * 指数退避重试：30s, 60s, 120s, 240s, 480s（最多 5 次），耗尽后标记死信
     * <p>
     * 死信指标（deadLetterCount）供 Prometheus 采集，触发告警规则：
     * 当 deadLetterCount > 0 时，需人工排查补偿。
     * </p>
     */
    private void handleRetryFailure(LocalMessage msg) {
        int retryCount = msg.getRetryCount() + 1;
        if (retryCount >= MAX_RETRIES) {
            msg.setStatus(3); // 死信
            long currentDeadCount = deadLetterCount.incrementAndGet();
            log.error("[本地消息] 重试耗尽，标记死信: msgId={}, transactionId={}, operationType={}, deadCount={}, 需人工介入！请检查下游服务是否正常",
                    msg.getId(), msg.getTransactionId(), msg.getOperationType(), currentDeadCount);
        } else {
            // 指数退避: 30s * 2^(retryCount-1)
            long delaySeconds = 30L * (1L << (retryCount - 1));
            msg.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
            msg.setRetryCount(retryCount);
            msg.setStatus(2); // 失败
            log.info("[本地消息] 指数退避重试: msgId={}, retry={}/{}, nextRetry={}s",
                msg.getId(), retryCount, MAX_RETRIES, delaySeconds);
        }
        localMessageMapper.updateById(msg);
    }

    /**
     * 根据操作类型解析目标 Topic
     */
    private String resolveTopic(String operationType) {
        return switch (operationType) {
            case "ORDER_CREATED" -> OrderService.ORDER_TRANSACTION_TOPIC;
            default -> "DEFAULT_RETRY_TOPIC";
        };
    }
}
