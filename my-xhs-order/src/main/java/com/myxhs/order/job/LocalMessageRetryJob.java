package com.myxhs.order.job;

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

import java.util.List;

/**
 * 本地消息表补发定时任务（XXL-Job 分布式调度）
 * <p>
 * 扫描 status=0（待处理）或 status=2（失败）的消息，重新发送到 MQ。
 * 重试 3 次后标记为死信（status=3），需人工介入。
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
     * 补发逻辑：扫描待处理消息 → 重新发送到 MQ → 更新状态
     */
    private void doRetry() {
        List<LocalMessage> messages = localMessageMapper.selectPendingMessages(BATCH_SIZE);
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
     * 处理重试失败：增加重试次数，超过 3 次标记为死信
     */
    private void handleRetryFailure(LocalMessage msg) {
        if (msg.getRetryCount() >= 2) {
            localMessageMapper.markDead(msg.getId());
            log.error("[本地消息] 标记死信(3次重试均失败): id={}, transactionId={}",
                    msg.getId(), msg.getTransactionId());
        } else {
            localMessageMapper.markFailed(msg.getId());
        }
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
