package com.myxhs.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;

@Slf4j
public abstract class DlqMessageHandler {

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

        onDlqMessage(messageExt, consumerGroup);
    }

    /**
     * 自定义告警钩子，子类可重写
     */
    protected void onDlqMessage(MessageExt messageExt, String consumerGroup) {
        // 默认空实现，由子类扩展（钉钉通知、企微通知等）
    }
}
