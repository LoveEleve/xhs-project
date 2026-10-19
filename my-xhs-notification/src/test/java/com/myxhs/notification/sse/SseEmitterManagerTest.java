package com.myxhs.notification.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 连接管理器单测（C-012）
 * <p>
 * 验证口径：建连注册路由键（30s TTL）、容量帽（50k → BizException）、本地直推/跨实例定向发布/离线、
 * 心跳续期 + 死连接清理（双参 remove 防误删新连接）。
 * </p>
 */
@SuppressWarnings("unchecked")
class SseEmitterManagerTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SseEmitterManager manager;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        manager = new SseEmitterManager(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("建连：注册路由键（serverId + 30s TTL）+ 在线")
    void createConnectionRegistersRoute() {
        manager.createConnection(9L);

        ArgumentCaptor<String> serverIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("myxhs:notification:sse:9"), serverIdCaptor.capture(), eq(Duration.ofSeconds(30)));
        assertThat(serverIdCaptor.getValue()).isNotBlank();
        assertThat(manager.isOnline(9L)).isTrue();
    }

    @Test
    @DisplayName("容量帽：达到 50k 且为新用户 → BizException")
    void maxConnectionsRejected() {
        Map<Long, SseEmitter> emitters = (Map<Long, SseEmitter>) ReflectionTestUtils.getField(manager, "emitters");
        SseEmitter one = new SseEmitter();
        for (long i = 0; i < 50_000; i++) {
            emitters.put(i, one);
        }

        assertThatThrownBy(() -> manager.createConnection(999_999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限");
    }

    @Test
    @DisplayName("本地直推：成功返回 true（emitter 缓冲发送）")
    void pushLocal() {
        manager.createConnection(9L);

        assertThat(manager.pushNotification(9L, Map.of("id", 500L))).isTrue();
    }

    @Test
    @DisplayName("离线：无本地无路由 → false")
    void pushOfflineReturnsFalse() {
        when(valueOps.get("myxhs:notification:sse:9")).thenReturn(null);
        assertThat(manager.pushNotification(9L, Map.of("id", 500L))).isFalse();
    }

    @Test
    @DisplayName("跨实例：路由指向其他实例 → 定向 Channel 发布")
    void pushCrossInstance() {
        when(valueOps.get("myxhs:notification:sse:9")).thenReturn("10.0.0.2:19013#42");

        boolean pushed = manager.pushNotification(9L, Map.of("id", 500L));

        assertThat(pushed).isTrue();
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(channelCaptor.capture(), anyString());
        assertThat(channelCaptor.getValue()).isEqualTo("myxhs:notification:sse:channel:10.0.0.2:19013#42");
    }

    @Test
    @DisplayName("心跳：死连接（send 抛异常）→ 清理映射 + 删路由键")
    void heartbeatCleansDeadConnection() throws Exception {
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new RuntimeException("broken pipe"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));
        Map<Long, SseEmitter> emitters = (Map<Long, SseEmitter>) ReflectionTestUtils.getField(manager, "emitters");
        emitters.put(9L, dead);

        manager.heartbeat();

        assertThat(manager.isOnline(9L)).isFalse();
        verify(redisTemplate).delete("myxhs:notification:sse:9");
    }

    @Test
    @DisplayName("心跳：Redis 续期失败不影响本地死连接清理（异常隔离）")
    void heartbeatIsolatesRedisFailure() throws Exception {
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new RuntimeException("broken pipe"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));
        Map<Long, SseEmitter> emitters = (Map<Long, SseEmitter>) ReflectionTestUtils.getField(manager, "emitters");
        emitters.put(9L, dead);
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenThrow(new RuntimeException("Redis down"));

        manager.heartbeat();

        assertThat(manager.isOnline(9L)).isFalse();
    }

    @Test
    @DisplayName("isOnlineAnywhere：本地优先；否则查路由键；Redis 异常按离线")
    void onlineAnywhere() {
        assertThat(manager.isOnlineAnywhere(9L)).isFalse();

        when(valueOps.get("myxhs:notification:sse:9")).thenReturn("x");
        when(redisTemplate.hasKey("myxhs:notification:sse:9")).thenReturn(true);
        assertThat(manager.isOnlineAnywhere(9L)).isTrue();

        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("down"));
        assertThat(manager.isOnlineAnywhere(9L)).isFalse();
    }
}
