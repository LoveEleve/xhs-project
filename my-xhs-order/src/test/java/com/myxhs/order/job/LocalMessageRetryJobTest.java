package com.myxhs.order.job;

import com.myxhs.order.service.OrderNotificationPublisher;
import com.myxhs.order.service.OrderService;
import com.myxhs.order.service.OrderTerminationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 本地消息补发 Job 单测（C-012 F6 增补）
 * <p>
 * 验证口径：operation → topic 映射（含新增 ORDER_STATUS_NOTIFICATION → NOTIFICATION_TOPIC）；
 * 未知类型拒绝投递（04B 口径回归）。
 * </p>
 */
class LocalMessageRetryJobTest {

    private LocalMessageRetryJob job;

    @BeforeEach
    void setUp() {
        job = new LocalMessageRetryJob(mock(com.myxhs.order.mapper.LocalMessageMapper.class),
                mock(StringRedisTemplate.class), mock(org.apache.rocketmq.spring.core.RocketMQTemplate.class),
                mock(io.micrometer.core.instrument.MeterRegistry.class));
    }

    @Test
    @DisplayName("C-012 F6：ORDER_STATUS_NOTIFICATION → NOTIFICATION_TOPIC")
    void statusNotificationTopicMapping() {
        String topic = ReflectionTestUtils.invokeMethod(job, "resolveTopic",
                OrderNotificationPublisher.OPERATION_TYPE_ORDER_STATUS_NOTIFICATION);
        assertThat(topic).isEqualTo("NOTIFICATION_TOPIC");
    }

    @Test
    @DisplayName("既有映射回归：ORDER_TERMINATED → ORDER_TERMINATION_TOPIC")
    void terminationTopicMapping() {
        String topic = ReflectionTestUtils.invokeMethod(job, "resolveTopic",
                OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED);
        assertThat(topic).isEqualTo(OrderService.ORDER_TERMINATION_TOPIC);
    }

    @Test
    @DisplayName("未知类型：拒绝投递（抛异常进退避/死信，人工可查）")
    void unknownTypeRejected() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(job, "resolveTopic", "UNKNOWN_TYPE"))
                .isInstanceOf(IllegalStateException.class);
    }
}
