package com.myxhs.coupon.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.coupon.constant.CouponMqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 券过期延时消息生产器（C-017）
 * <p>
 * RocketMQ 开源版延时消息窗口默认 **24 小时**（超出范围会被立即投递）。
 * 因此采用"分段延时"：每次最多排 23 小时；消费端发现还没到期就再排一段，
 * 直到进入 24h 窗口后按 validEnd 精确投递；XXL-Job 始终兜底。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireDelayProducer {

    /** 单段最大延时（留 1h 安全边界，避免 24h 上限导致立即投递） */
    public static final long MAX_WINDOW_MS = 23L * 60 * 60 * 1000;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public void schedule(Long templateId, LocalDateTime validEnd) {
        if (templateId == null || validEnd == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long validEndMs = validEnd.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (validEndMs <= now) {
            return;
        }
        long deliverAt = Math.min(validEndMs, now + MAX_WINDOW_MS);
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "templateId", templateId,
                    "validEnd", validEnd.toString()));
            Message<String> message = MessageBuilder.withPayload(body)
                    .setHeader(RocketMQHeaders.KEYS, "COUPON_EXPIRE_" + templateId)
                    .build();
            rocketMQTemplate.syncSendDeliverTimeMills(
                    CouponMqConstants.COUPON_EXPIRE_TOPIC, message, deliverAt);
            log.info("[券过期延时] 已排期: templateId={}, validEnd={}, deliverAt={}, chained={}",
                    templateId, validEnd,
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(deliverAt), ZoneId.systemDefault()),
                    validEndMs > now + MAX_WINDOW_MS);
        } catch (Exception e) {
            log.error("[券过期延时] 排期失败(Job 兜底): templateId={}", templateId, e);
        }
    }
}
