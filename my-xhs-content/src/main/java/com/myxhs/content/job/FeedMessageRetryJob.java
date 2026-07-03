package com.myxhs.content.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.entity.NotePublishEvent;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Feed 消息补发任务（定时扫描 + 重试）
 * <p>
 * 每 30 秒扫描本地消息表中未发送成功的 Feed 推送消息，重新投递到 MQ。
 * 3 次重试后标记为死信（status=3）。
 * </p>
 * <p>
 * 多实例安全：Feed ZADD 天然幂等，重复推送不产生副作用。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedMessageRetryJob {

    private final LocalMessageMapper localMessageMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    private static final int RETRY_DELAY_SECONDS = 60;
    private static final int MAX_RETRY = 3;
    private static final int SCAN_LIMIT = 100;

    @Scheduled(fixedRate = 30000)
    public void retryFailedMessages() {
        try {
            int sent = 0;
            int failed = 0;

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(RETRY_DELAY_SECONDS);
            List<LocalMessage> messages = localMessageMapper.selectPending(cutoffTime, MAX_RETRY, SCAN_LIMIT);

            for (LocalMessage msg : messages) {
                try {
                    NotePublishEvent event = objectMapper.readValue(msg.getBody(), NotePublishEvent.class);
                    rocketMQTemplate.syncSend(msg.getTopic(), event, 3000);

                    localMessageMapper.markSent(msg.getId());
                    sent++;
                    log.info("[Feed补偿] 补发成功: localMsgId={}", msg.getId());

                } catch (Exception e) {
                    localMessageMapper.incrementRetry(msg.getId(), MAX_RETRY);
                    failed++;
                    log.warn("[Feed补偿] 补发失败: localMsgId={}, retryCount={}", msg.getId(), msg.getRetryCount());
                }
            }

            if (sent > 0 || failed > 0) {
                log.info("[Feed补偿] 执行完成: 成功={}, 失败={}, 扫描={}", sent, failed, messages.size());
            }

        } catch (Exception e) {
            log.error("[Feed补偿] 执行异常", e);
        }
    }
}
