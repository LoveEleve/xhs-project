package com.myxhs.coupon.push;

import com.myxhs.common.metrics.BusinessMetrics;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CouponPushSenderTest {

    @Test
    @DisplayName("发送：NOTIFICATION_TOPIC + 系统通知字段")
    @SuppressWarnings("unchecked")
    void sendPayload() {
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        BusinessMetrics metrics = mock(BusinessMetrics.class);
        CouponPushSender sender = new CouponPushSender(rocketMQTemplate, metrics);

        boolean ok = sender.send(new CouponPushTarget(1L, "满100减10", 1001L, LocalDateTime.now().plusDays(1)));

        assertThat(ok).isTrue();
        verify(rocketMQTemplate).syncSend(eq("NOTIFICATION_TOPIC"), any(Map.class));
        verify(metrics).recordCouponAction("push_ok", true);
    }
}
