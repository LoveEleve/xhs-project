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
 * 每 30 秒执行两类扫描：
 * </p>
 * <ul>
 *   <li><b>MQ 发送失败重试</b>：扫描 status=0（待发送）的消息，重新投递到 MQ。
 *       3 次重试后标记为死信（status=3）。</li>
 *   <li><b>Feed 推送未完成补偿</b>：扫描 status=1 AND push_status IN (0,1)（MQ 已发送但 Feed 推送未完成）
 *       的消息，重新投递到 MQ。FeedPushConsumer 从 Redis 断点恢复继续推送。</li>
 * </ul>
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
    private static final int PUSH_COMPENSATE_SCAN_LIMIT = 50;
    private static final int PUSH_COMPENSATE_DELAY_SECONDS = 120;

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

    /**
     * Feed 推送未完成补偿：扫描 MQ 已发送但 Feed 推送未完成的消息，重新投递到 MQ。
     * <p>
     * 触发条件：消息 status=1（MQ 已发送）且 push_status IN (0,1)（推送未完成或推送中），
     * 且距离创建时间超过 {@link #PUSH_COMPENSATE_DELAY_SECONDS}（避免刚发布就触发补偿）。
     * </p>
     * <p>
     * 断点续推机制：
     * FeedPushConsumer 每批 Pipeline 完成后将进度写入 Redis（key=myxhs:feed:push:progress:{localMsgId}），
     * MQ 重试时 Consumer 从 Redis 读取上次进度继续推送，不从头开始。
     * </p>
     */
    @Scheduled(fixedRate = 60000)
    public void compensateIncompletePush() {
        try {
            int resent = 0;
            int failed = 0;
            int skipped = 0;

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(PUSH_COMPENSATE_DELAY_SECONDS);
            List<LocalMessage> messages = localMessageMapper.selectPendingPushWithDelay(
                    cutoffTime, PUSH_COMPENSATE_SCAN_LIMIT);

            for (LocalMessage msg : messages) {
                try {
                    NotePublishEvent event = objectMapper.readValue(msg.getBody(), NotePublishEvent.class);
                    // 确保 localMsgId 已设置，FeedPushConsumer 需要它进行断点恢复
                    if (event.getLocalMsgId() == null) {
                        event.setLocalMsgId(msg.getId());
                    }
                    rocketMQTemplate.syncSend(msg.getTopic(), event, 3000);

                    // 标记推送中（幂等：Consumer 从 Redis 断点恢复，重复推送无副作用）
                    localMessageMapper.updatePushProgress(msg.getId(), 1, msg.getPushCursor() != null ? msg.getPushCursor() : 0);
                    resent++;
                    log.info("[Feed推送补偿] 重新投递: localMsgId={}, noteId={}, pushStatus={}, pushCursor={}",
                            msg.getId(), event.getNoteId(), msg.getPushStatus(), msg.getPushCursor());

                } catch (Exception e) {
                    failed++;
                    log.warn("[Feed推送补偿] 重新投递失败: localMsgId={}", msg.getId(), e);
                }
            }

            if (resent > 0 || failed > 0) {
                log.info("[Feed推送补偿] 执行完成: 重投={}, 失败={}, 扫描={}", resent, failed, messages.size());
            }

        } catch (Exception e) {
            log.error("[Feed推送补偿] 执行异常", e);
        }
    }
}
