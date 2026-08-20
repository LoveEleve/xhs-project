package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.service.CouponService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
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

    private CouponClaimConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CouponClaimConsumer(userCouponMapper, templateMapper,
                new ObjectMapper(), idempotentHelper);
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
    }

    private MessageExt message(String msgId, String body) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
