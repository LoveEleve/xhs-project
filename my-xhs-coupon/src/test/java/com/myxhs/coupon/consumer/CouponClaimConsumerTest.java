package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.service.CouponService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponClaimConsumerTest {

    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private CouponTemplateMapper templateMapper;
    @Mock
    private MessageIdempotentHelper idempotentHelper;
    @Mock
    private BusinessMetrics businessMetrics;

    private CouponClaimConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CouponClaimConsumer(userCouponMapper, templateMapper,
                new ObjectMapper(), idempotentHelper, businessMetrics);
    }

    @Test
    void stockDecrementFailureFailsConsumption() {
        MessageExt message = message("msg-1", "{\"userId\":1001,\"templateId\":1,\"claimNo\":\"claim-1\"}");
        when(idempotentHelper.isFirstProcess("coupon:claim", "msg-1", 86400L)).thenReturn(true);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenReturn(1);
        when(templateMapper.decrementRemainCount(1L)).thenReturn(0);

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("领券消费失败");

        verify(templateMapper).decrementRemainCount(1L);
        verify(idempotentHelper).removeMark("coupon:claim", "msg-1");
        verify(businessMetrics).recordCouponAction("claim_persist_fail", false);
    }

    @Test
    @DisplayName("C-007 G35：DB 已持有 ≥ perUserLimit → 守卫跳过（不 insert、不 decrement）")
    void limitGuardSkipsWhenAlreadyAtLimit() {
        MessageExt message = message("msg-g1", "{\"userId\":1001,\"templateId\":1,\"claimNo\":\"claim-g1\"}");
        when(idempotentHelper.isFirstProcess("coupon:claim", "msg-g1", 86400L)).thenReturn(true);
        CouponTemplate template = new CouponTemplate();
        template.setPerUserLimit(1);
        when(templateMapper.selectById(1L)).thenReturn(template);
        when(userCouponMapper.countIssued(1001L, 1L)).thenReturn(1L);

        consumer.onMessage(message);

        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
        verify(templateMapper, never()).decrementRemainCount(anyLong());
        verify(businessMetrics).recordCouponAction("claim_limit_guard_skip", true);
    }

    @Test
    @DisplayName("C-007 G35：DB 未达限领 → 正常发券")
    void limitGuardPassesWhenBelowLimit() {
        MessageExt message = message("msg-g2", "{\"userId\":1001,\"templateId\":1,\"claimNo\":\"claim-g2\"}");
        when(idempotentHelper.isFirstProcess("coupon:claim", "msg-g2", 86400L)).thenReturn(true);
        CouponTemplate template = new CouponTemplate();
        template.setPerUserLimit(2);
        when(templateMapper.selectById(1L)).thenReturn(template);
        when(userCouponMapper.countIssued(1001L, 1L)).thenReturn(1L);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenReturn(1);
        when(templateMapper.decrementRemainCount(1L)).thenReturn(1);

        consumer.onMessage(message);

        verify(userCouponMapper).insert(any(UserCoupon.class));
        verify(businessMetrics).recordCouponAction("claim_persist_ok", true);
    }

    private MessageExt message(String msgId, String body) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
