package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
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
 * 幂等保证（双重）：
 * 1. MessageIdempotentHelper（msgId, 24h）快速去重，避免重复消费
 * 2. t_user_coupon 表唯一索引 uk_claim_no(claim_no) 兜底去重
 * 支持 perUserLimit > 1：同一用户可多次领取同一模板，每次消息有唯一 msgId
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "COUPON_CLAIM_TOPIC",
        consumerGroup = "coupon-claim-consumer-group",
        maxReconsumeTimes = 5
)
public class CouponClaimConsumer implements RocketMQListener<MessageExt> {

    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;

    private static final String BIZ_TYPE = "coupon:claim";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String msgId = msg.getMsgId();

            // 1. 统一幂等检查
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msgId, IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            String body = new String(msg.getBody());
            CouponService.CouponClaimEvent event = objectMapper.readValue(body, CouponService.CouponClaimEvent.class);

            log.info("[优惠券MQ] 收到领券消息: userId={}, templateId={}, msgId={}",
                    event.userId(), event.templateId(), msgId);

            // 2. 写入用户券表（claimNo=msgId 保证幂等唯一）
            UserCoupon userCoupon = new UserCoupon();
            userCoupon.setUserId(event.userId());
            userCoupon.setCouponId(event.templateId());
            userCoupon.setClaimNo(msgId);
            userCoupon.setStatus(0); // 未使用

            try {
                userCouponMapper.insert(userCoupon);
            } catch (DuplicateKeyException e) {
                // 兜底幂等：唯一索引 uk_claim_no(claim_no) 冲突，说明已写入过
                log.warn("[优惠券MQ] 重复领券记录(DB唯一索引兜底): userId={}, templateId={}, msgId={}",
                        event.userId(), event.templateId(), msgId);
                return;
            }

            // 3. 扣减模板剩余数量
            templateMapper.decrementRemainCount(event.templateId());

            log.info("[优惠券MQ] 领券持久化成功: userId={}, templateId={}, userCouponId={}",
                    event.userId(), event.templateId(), userCoupon.getId());

        } catch (Exception e) {
            // insert 或 decrementRemainCount 失败时不设置 SETNX，MQ 重试可重新执行完整流程
            log.error("[优惠券MQ] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("领券消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
