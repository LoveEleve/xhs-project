package com.myxhs.order.consumer;

import com.myxhs.order.entity.OrderNoMapping;
import com.myxhs.order.repository.OrderNoMappingRepository;
import com.myxhs.order.service.OrderService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCompensationConsumerTest {

    @Mock
    private OrderService orderService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private OrderNoMappingRepository orderNoMappingRepository;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private OrderCompensationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderCompensationConsumer(orderService, stringRedisTemplate, orderNoMappingRepository);
    }

    @Test
    void shouldRetryWhenMappingMissingAndMessageHasNoUserId() {
        MessageExt message = compensationMessage("msg-1", "{\"action\":\"RELEASE_STOCK\",\"orderId\":123456,\"timestamp\":1}");
        when(stringRedisTemplate.hasKey("order:compensation:consumed:msg-1")).thenReturn(false);
        when(orderNoMappingRepository.selectByOrderId(123456L)).thenReturn(null);

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("关单补偿失败");

        verify(orderService, never()).compensateReleaseStock(anyLong(), anyLong());
        verify(valueOperations, never()).set(anyString(), anyString(), any());
    }

    @Test
    void shouldRetryWhenUserIdPropertyIsInvalidAndMappingMissing() {
        MessageExt message = compensationMessage("msg-2", "{\"action\":\"RETURN_COUPON\",\"orderId\":123456,\"timestamp\":1}");
        message.putUserProperty("userId", "bad-user-id");
        when(stringRedisTemplate.hasKey("order:compensation:consumed:msg-2")).thenReturn(false);
        when(orderNoMappingRepository.selectByOrderId(123456L)).thenReturn(null);

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("关单补偿失败");

        verify(orderService, never()).compensateReturnCoupon(anyLong(), anyLong());
        verify(valueOperations, never()).set(anyString(), anyString(), any());
    }

    @Test
    void shouldUseMappingUserIdWhenHeaderMissing() {
        MessageExt message = compensationMessage("msg-3", "{\"action\":\"RELEASE_STOCK\",\"orderId\":123456,\"timestamp\":1}");
        when(stringRedisTemplate.hasKey("order:compensation:consumed:msg-3")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        OrderNoMapping mapping = new OrderNoMapping();
        mapping.setOrderId(123456L);
        mapping.setUserId(1001L);
        when(orderNoMappingRepository.selectByOrderId(123456L)).thenReturn(mapping);

        consumer.onMessage(message);

        verify(orderService).compensateReleaseStock(123456L, 1001L);
        verify(valueOperations).set(eq("order:compensation:consumed:msg-3"), eq("1"), any());
    }

    private MessageExt compensationMessage(String msgId, String body) {
        MessageExt message = new MessageExt();
        message.setMsgId(msgId);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
