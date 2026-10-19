package com.myxhs.notification.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SSE 跨实例订阅者单测（C-012）
 * <p>
 * 验证口径：Long→String 序列化下 userId 解析（2026-09-20 修复回归）；格式异常不抛出。
 * </p>
 */
class SseCrossInstanceSubscriberTest {

    private SseEmitterManager sseEmitterManager;
    private SseCrossInstanceSubscriber subscriber;

    @BeforeEach
    void setUp() {
        sseEmitterManager = mock(SseEmitterManager.class);
        subscriber = new SseCrossInstanceSubscriber(sseEmitterManager,
                mock(StringRedisTemplate.class), new ObjectMapper());
    }

    private static DefaultMessage message(String body) {
        return new DefaultMessage("channel".getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Long→String 序列化：userId 字符串可解析（修复回归）")
    void parsesStringUserId() {
        subscriber.onMessage(message("{\"userId\":\"9\",\"event\":\"notification\",\"data\":{\"id\":500}}"), null);

        verify(sseEmitterManager).handleCrossInstanceMessageJson(eq(9L), eq("notification"), anyString());
    }

    @Test
    @DisplayName("数字型 userId 亦兼容；data 保留原始 JSON")
    void parsesNumericUserId() {
        subscriber.onMessage(message("{\"userId\":9,\"event\":\"unread-count\",\"data\":{\"total\":3}}"), null);

        verify(sseEmitterManager).handleCrossInstanceMessageJson(eq(9L), eq("unread-count"), eq("{\"total\":3}"));
    }

    @Test
    @DisplayName("格式异常（无 userId）→ 忽略不抛出")
    void malformedIgnored() {
        subscriber.onMessage(message("{\"event\":\"notification\"}"), null);

        verify(sseEmitterManager, never()).handleCrossInstanceMessageJson(any(), anyString(), anyString());
    }
}
