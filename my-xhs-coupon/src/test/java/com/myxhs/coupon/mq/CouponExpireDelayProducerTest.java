package com.myxhs.coupon.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CouponExpireDelayProducerTest {

    @Test
    @DisplayName("超过 24h 窗口：按 now+23h 分段排期（不直接排到 validEnd）")
    void capToWindow() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CouponExpireDelayProducer producer = new CouponExpireDelayProducer(template, new ObjectMapper());
        LocalDateTime validEnd = LocalDateTime.now().plusDays(7);
        long before = System.currentTimeMillis();

        producer.schedule(1L, validEnd);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(template).syncSendDeliverTimeMills(eq("COUPON_EXPIRE_TOPIC"), any(), captor.capture());
        long deliverAt = captor.getValue();
        assertThat(deliverAt).isBetween(before + CouponExpireDelayProducer.MAX_WINDOW_MS - 5000,
                System.currentTimeMillis() + CouponExpireDelayProducer.MAX_WINDOW_MS + 5000);
    }

    @Test
    @DisplayName("24h 内：按 validEnd 精确排期")
    void withinWindow() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CouponExpireDelayProducer producer = new CouponExpireDelayProducer(template, new ObjectMapper());
        LocalDateTime validEnd = LocalDateTime.now().plusHours(1);

        producer.schedule(1L, validEnd);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(template).syncSendDeliverTimeMills(eq("COUPON_EXPIRE_TOPIC"), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                validEnd.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    @Test
    @DisplayName("已过期：不排期")
    void pastIgnored() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        CouponExpireDelayProducer producer = new CouponExpireDelayProducer(template, new ObjectMapper());

        producer.schedule(1L, LocalDateTime.now().minusMinutes(1));

        verify(template, never()).syncSendDeliverTimeMills(any(), any(), anyLong());
    }
}
