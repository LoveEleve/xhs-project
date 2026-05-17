package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 领券事件消费者（MQ 异步写 MySQL）
 * <p>
 * 消费 COUPON_CLAIM_TOPIC 消息，将领券记录持久化到 MySQL。
 * </p>
 * <p>
 * 幂等保证：
 * t_user_coupon 表有唯一索引 uk_user_coupon(user_id, coupon_id)。
 * 重复消费时 INSERT 会抛 DuplicateKeyException，捕获后忽略。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "COUPON_CLAIM_TOPIC",
        consumerGroup = "coupon-claim-consumer-group"
)
public class CouponClaimConsumer implements RocketMQListener<MessageExt> {

    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody());
            CouponService.CouponClaimEvent event = objectMapper.readValue(body, CouponService.CouponClaimEvent.class);

            log.info("[优惠券MQ] 收到领券消息: userId={}, templateId={}",
                    event.userId(), event.templateId());

            // 1. 写入用户券表
            UserCoupon userCoupon = new UserCoupon();
            userCoupon.setUserId(event.userId());
            userCoupon.setCouponId(event.templateId());
            userCoupon.setStatus(0); // 未使用

            try {
                userCouponMapper.insert(userCoupon);
            } catch (DuplicateKeyException e) {
                // 幂等：唯一索引冲突，说明已经写入过，忽略
                log.warn("[优惠券MQ] 重复领券记录(幂等忽略): userId={}, templateId={}",
                        event.userId(), event.templateId());
                return;
            }

            // 2. 扣减模板剩余数量
            templateMapper.decrementRemainCount(event.templateId());

            log.info("[优惠券MQ] 领券持久化成功: userId={}, templateId={}, userCouponId={}",
                    event.userId(), event.templateId(), userCoupon.getId());

        } catch (Exception e) {
            log.error("[优惠券MQ] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("领券消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
