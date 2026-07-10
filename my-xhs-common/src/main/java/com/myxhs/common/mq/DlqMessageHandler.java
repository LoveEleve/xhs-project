package com.myxhs.common.mq;

import com.myxhs.common.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;

@Slf4j
public abstract class DlqMessageHandler {

    /** 静态持有 BusinessMetrics，由 Spring 容器启动时注入 */
    static volatile BusinessMetrics businessMetrics;

    /**
     * 由 Spring 容器在 BusinessMetrics Bean 初始化后调用
     */
    public static void setBusinessMetrics(BusinessMetrics metrics) {
        businessMetrics = metrics;
    }

    /**
     * 处理 DLQ 消息的模板方法
     * @param messageExt 死信消息
     * @param consumerGroup 消费者组名
     */
    protected void handleDlqMessage(MessageExt messageExt, String consumerGroup) {
        log.error("[DLQ] 死信消息 | consumerGroup={} | msgId={} | topic={} | tags={} | keys={} | bornTimestamp={} | reconsumeTimes={} | body={}",
                consumerGroup,
                messageExt.getMsgId(),
                messageExt.getTopic(),
                messageExt.getTags(),
                messageExt.getKeys(),
                messageExt.getBornTimestamp(),
                messageExt.getReconsumeTimes(),
                new String(messageExt.getBody()));

        // 记录 DLQ 指标到 Prometheus
        if (businessMetrics != null) {
            businessMetrics.recordDlqMessage(consumerGroup, messageExt.getTopic());
        }

        onDlqMessage(messageExt, consumerGroup);
    }

    /**
     * 自定义告警钩子，子类可重写（钉钉通知、企微通知等）
     */
    protected void onDlqMessage(MessageExt messageExt, String consumerGroup) {
        // 默认空实现，由子类扩展
    }
}
