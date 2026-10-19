package com.myxhs.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * 本地消息入队助手（C-010：统一"同事务入队 + afterCommit 发送 + 成功标记"三段式）
 * <p>
 * 原实现散落于 publishNote/publishDraft（重复块），删除链路事件则无可靠性层（fire-and-forget）；
 * 统一后：发布（FEED_TOPIC）与删除链路（UNCOMMENT/NOTE_DELETE）共用同一机制。
 * </p>
 * <ul>
 *   <li>事务内：insert 占位（body="{}"）→ 用生成的 ID 构建载荷 → 回填 body（与业务写同事务）；</li>
 *   <li>afterCommit：asyncSend → onSuccess：markSent；onException：仅日志（行保留 → FeedMessageRetryJob 补发）。</li>
 * </ul>
 * <p>调用方必须处于事务中（enqueue 内断言），保证"业务与消息同事务"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMessageHelper {

    private final LocalMessageMapper localMessageMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 入队一条本地消息（同事务），提交后异步发送并标记。
     *
     * @param topic          目标 topic
     * @param payloadBuilder 载荷构建器（入参=本地消息 ID；FEED_TOPIC 需要它做断点续推锚点）
     */
    public void enqueue(String topic, Function<Long, Object> payloadBuilder) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("LocalMessageHelper.enqueue 必须在事务内调用: topic=" + topic);
        }
        LocalMessage row = new LocalMessage();
        row.setTopic(topic);
        row.setBody("{}");
        row.setStatus(0);
        row.setRetryCount(0);
        row.setCreatedAt(LocalDateTime.now());
        localMessageMapper.insert(row);

        Object payload = payloadBuilder.apply(row.getId());
        row.setBody(toJson(payload));
        localMessageMapper.updateById(row);

        final Long localMsgId = row.getId();
        final String body = row.getBody();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    rocketMQTemplate.asyncSend(topic,
                            MqTraceHelper.wrapWithTraceContext(MessageBuilder.withPayload(body).build()),
                            new org.apache.rocketmq.client.producer.SendCallback() {
                                @Override
                                public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                                    localMessageMapper.markSent(localMsgId);
                                }

                                @Override
                                public void onException(Throwable e) {
                                    // 行保留（status=0）→ FeedMessageRetryJob 补发接管
                                    log.error("[本地消息] 发送失败(补发任务接管): localMsgId={}, topic={}", localMsgId, topic, e);
                                }
                            });
                } catch (Exception e) {
                    log.error("[本地消息] 发送异常(补发任务接管): localMsgId={}, topic={}", localMsgId, topic, e);
                }
            }
        });
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.INTERNAL_ERROR, "数据序列化失败");
        }
    }
}
