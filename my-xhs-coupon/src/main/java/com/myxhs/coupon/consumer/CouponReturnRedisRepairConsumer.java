package com.myxhs.coupon.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "COUPON_RETURN_REDIS_REPAIR_TOPIC",
        consumerGroup = "coupon-return-redis-repair-consumer-group",
        maxReconsumeTimes = 5
)
public class CouponReturnRedisRepairConsumer implements RocketMQListener<MessageExt> {

    private final CouponService couponService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            CouponService.CouponReturnRedisRepairEvent event = JSON.parseObject(body,
                    CouponService.CouponReturnRedisRepairEvent.class);
            if (event == null || event.templateId() == null || event.userId() == null) {
                log.warn("[优惠券] 退券Redis补偿脏消息跳过: msgId={}", msg.getMsgId());
                return;
            }
            couponService.repairReturnCouponRedis(event.userId(), event.templateId());
            log.info("[优惠券] 退券Redis补偿成功: userId={}, templateId={}", event.userId(), event.templateId());
        } catch (Exception e) {
            log.error("[优惠券] 退券Redis补偿失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("退券Redis补偿失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
