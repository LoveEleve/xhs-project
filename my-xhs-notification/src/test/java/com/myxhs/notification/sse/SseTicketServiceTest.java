package com.myxhs.notification.sse;

import com.myxhs.notification.service.SseTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 票据服务单测（C-012）：30s 一次性（getAndDelete 原子消费）。
 */
class SseTicketServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SseTicketService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SseTicketService(redisTemplate);
    }

    @Test
    @DisplayName("生成票据：随机 + 30s TTL + 值=userId")
    void generateTicket() {
        String ticket = service.generateTicket(9L);

        assertThat(ticket).isNotBlank();
        verify(valueOps).set(eq("myxhs:notification:sse:ticket:" + ticket), eq("9"), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("消费票据：getAndDelete 原子一次性")
    void consumeTicket() {
        when(valueOps.getAndDelete("myxhs:notification:sse:ticket:abc")).thenReturn("9");

        assertThat(service.validateAndConsume("abc")).isEqualTo(9L);
    }

    @Test
    @DisplayName("票据不存在/已使用 → null；脏值 → null")
    void invalidTicket() {
        when(valueOps.getAndDelete("myxhs:notification:sse:ticket:abc")).thenReturn(null);
        assertThat(service.validateAndConsume("abc")).isNull();

        when(valueOps.getAndDelete("myxhs:notification:sse:ticket:bad")).thenReturn("not-a-number");
        assertThat(service.validateAndConsume("bad")).isNull();

        assertThat(service.validateAndConsume("")).isNull();
    }
}
