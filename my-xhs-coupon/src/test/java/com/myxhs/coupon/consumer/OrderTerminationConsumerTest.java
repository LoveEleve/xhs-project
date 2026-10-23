package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.coupon.service.CouponService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderTerminationConsumerTest {

    private CouponService couponService;
    private MessageIdempotentHelper idempotentHelper;
    private OrderTerminationConsumer consumer;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        idempotentHelper = mock(MessageIdempotentHelper.class);
        consumer = new OrderTerminationConsumer(couponService, new ObjectMapper(), idempotentHelper);
    }

    private MessageExt message(String orderNo) {
        MessageExt msg = new MessageExt();
        msg.setMsgId("msg-" + orderNo);
        msg.setKeys(orderNo);
        msg.setBody(("{\"userId\":1001,\"orderId\":9001,\"couponId\":11}")
                .getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @Test
    @DisplayName("退券成功：处理后置完成态标记")
    void returnCouponSuccessMarksProcessed() {
        when(idempotentHelper.isProcessed("coupon:order-termination:consumed", "O1001")).thenReturn(false);

        consumer.onMessage(message("O1001"));

        verify(couponService).returnCoupon(eq(1001L), any());
        verify(idempotentHelper).markProcessed("coupon:order-termination:consumed", "O1001", 86400L);
    }

    @Test
    @DisplayName("已处理：直接跳过")
    void alreadyProcessedSkips() {
        when(idempotentHelper.isProcessed("coupon:order-termination:consumed", "O1001")).thenReturn(true);

        consumer.onMessage(message("O1001"));

        verifyNoInteractions(couponService);
        verify(idempotentHelper, never()).markProcessed(anyString(), anyString(), anyLong());
    }
}
