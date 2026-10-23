package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.constant.CouponMqConstants;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.mq.CouponExpireDelayProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 模板结束延时消息消费者（C-017）
 * <p>
 * 到点后把该模板下未使用券批量置过期（0→2）。
 * 幂等：条件更新（status=0）；消息提前到达（validEnd 未到）直接忽略；
 * 兜底：CouponExpireJob 每 5 分钟扫描，消息丢失也能收敛。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = CouponMqConstants.COUPON_EXPIRE_TOPIC,
        consumerGroup = "coupon-expire-consumer-group",
        maxReconsumeTimes = 3
)
public class CouponExpireDelayConsumer implements RocketMQListener<MessageExt> {

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final ObjectMapper objectMapper;
    private final CouponExpireDelayProducer expireDelayProducer;

    private static final int BATCH_SIZE = 1000;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            JsonNode node = objectMapper.readTree(new String(msg.getBody(), StandardCharsets.UTF_8));
            long templateId = node.path("templateId").asLong(0);
            if (templateId <= 0) {
                log.warn("[券过期延时] 消息缺少 templateId: msgId={}", msg.getMsgId());
                return;
            }
            CouponTemplate template = templateMapper.selectById(templateId);
            if (template == null || (template.getDeleted() != null && template.getDeleted() == 1)) {
                log.info("[券过期延时] 模板不存在/已删除，忽略: templateId={}", templateId);
                return;
            }
            if (template.getValidEnd() != null && template.getValidEnd().isAfter(LocalDateTime.now())) {
                // RocketMQ 24h 窗口：还没到期就再排一段（分段延时；Job 仍兜底）
                log.info("[券过期延时] 未到期，续排下一段: templateId={}, validEnd={}",
                        templateId, template.getValidEnd());
                expireDelayProducer.schedule(templateId, template.getValidEnd());
                return;
            }

            int total = 0;
            while (true) {
                int affected = userCouponMapper.batchExpireByCoupon(templateId, BATCH_SIZE);
                total += affected;
                if (affected < BATCH_SIZE) {
                    break;
                }
                Thread.sleep(100);
            }
            log.info("[券过期延时] 模板券已置过期: templateId={}, affected={}", templateId, total);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[券过期延时] 消费被中断: msgId={}", msg.getMsgId());
        } catch (Exception e) {
            log.error("[券过期延时] 消费失败(触发重试): msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("券过期延时消费失败", e);
        }
    }
}
