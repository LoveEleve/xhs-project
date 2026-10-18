package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.entity.CouponTemplate;
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
import org.springframework.transaction.annotation.Transactional;
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
    private final BusinessMetrics businessMetrics;

    private static final String BIZ_TYPE = "coupon:claim";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        String msgId = msg.getMsgId();
        try {

            // 1. 统一幂等检查
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msgId, IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            String body = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            CouponService.CouponClaimEvent event = objectMapper.readValue(body, CouponService.CouponClaimEvent.class);

            log.info("[优惠券MQ] 收到领券消息: userId={}, templateId={}, msgId={}",
                    event.userId(), event.templateId(), msgId);

            // 2. 【C-007 G35】投递时限领守卫：DB 计数 ≥ perUserLimit → 跳过 + 告警
            //    正常路径永不触发（Redis 闸门已保证）；防"DLQ 重放 × 对账修正后重领"交叉的超限发放。
            //    stock 漏损为安全方向（少发不超发），对账以 DB 为准收敛（修 claimed）。
            CouponTemplate template = templateMapper.selectById(event.templateId());
            if (template != null && template.getPerUserLimit() != null) {
                Long issued = userCouponMapper.countIssued(event.userId(), event.templateId());
                if (issued != null && issued >= template.getPerUserLimit()) {
                    log.warn("[优惠券MQ] 限领守卫拦截(已持有 {} 张 ≥ 限领 {}，跳过发券): userId={}, templateId={}, msgId={}",
                            issued, template.getPerUserLimit(), event.userId(), event.templateId(), msgId);
                    businessMetrics.recordCouponAction("claim_limit_guard_skip", true);
                    return;
                }
            }

            // 3. 写入用户券表（claimNo 优先，msgId 兜底——兼容 Outbox 重发）
            String claimNo = event.claimNo() != null ? event.claimNo() : msgId;
            UserCoupon userCoupon = new UserCoupon();
            userCoupon.setUserId(event.userId());
            userCoupon.setCouponId(event.templateId());
            userCoupon.setClaimNo(claimNo);
            userCoupon.setStatus(0);

            try {
                userCouponMapper.insert(userCoupon);
            } catch (DuplicateKeyException e) {
                // 兜底幂等：唯一索引 uk_claim_no(claim_no) 冲突，说明已写入过
                log.warn("[优惠券MQ] 重复领券记录(DB唯一索引兜底): userId={}, templateId={}, msgId={}",
                        event.userId(), event.templateId(), msgId);
                businessMetrics.recordCouponAction("claim_persist_duplicate", true);
                return;
            }

            // 4. 扣减模板剩余数量
            int affected = templateMapper.decrementRemainCount(event.templateId());
            if (affected != 1) {
                throw new IllegalStateException("优惠券库存扣减失败: templateId=" + event.templateId());
            }
            businessMetrics.recordCouponAction("claim_persist_ok", true);

            log.info("[优惠券MQ] 领券持久化成功: userId={}, templateId={}, userCouponId={}, stockUpdated={}",
                    event.userId(), event.templateId(), userCoupon.getId());

        } catch (Exception e) {
            // 清除幂等标记——失败时记录告警但不吞掉原异常，让 MQ 仍可重试
            try {
                idempotentHelper.removeMark(BIZ_TYPE, msgId);
            } catch (Exception markEx) {
                log.error("[优惠券MQ] 清除幂等标记失败(msgId可能已被TTL过期或Redis不可用): msgId={}", msgId, markEx);
            }
            businessMetrics.recordCouponAction("claim_persist_fail", false);
            log.error("[优惠券MQ] 消费失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("领券消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
