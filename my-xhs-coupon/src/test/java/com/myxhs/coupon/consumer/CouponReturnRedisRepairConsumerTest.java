package com.myxhs.coupon.consumer;

import com.myxhs.coupon.service.CouponService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CouponReturnRedisRepairConsumerTest {

    @Mock
    private CouponService couponService;

    private CouponReturnRedisRepairConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CouponReturnRedisRepairConsumer(couponService);
    }

    @Test
    void shouldReplayRedisRepairFromMessage() {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-1");
        message.setBody("{\"userId\":1001,\"templateId\":1}".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(message);

        verify(couponService).repairReturnCouponRedis(1001L, 1L);
    }

    @Test
    void shouldSkipDirtyRepairMessage() {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-2");
        message.setBody("{\"userId\":1001}".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(message);

        verify(couponService, never()).repairReturnCouponRedis(1001L, 1L);
    }
}
