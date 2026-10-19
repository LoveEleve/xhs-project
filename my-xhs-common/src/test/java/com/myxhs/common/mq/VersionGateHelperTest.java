package com.myxhs.common.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VersionGateHelper 单测（C-009 D3：完成态版本门语义矩阵）
 */
@ExtendWith(MockitoExtension.class)
class VersionGateHelperTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private VersionGateHelper helper;

    private static final String KEY = "myxhs:test:version:1";

    @BeforeEach
    void setUp() {
        helper = new VersionGateHelper(stringRedisTemplate);
    }

    @Test
    @DisplayName("isStale：eventTime 缺失 → 不拦截")
    void staleNullEventTime() {
        assertThat(helper.isStale(KEY, null)).isFalse();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("isStale：无版本 → 不拦截")
    void staleNoVersion() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(null);
        assertThat(helper.isStale(KEY, 1000L)).isFalse();
    }

    @Test
    @DisplayName("isStale：已推进版本 >= 事件时间 → 旧事件（拦截）")
    void staleOlderEvent() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn("2000");
        assertThat(helper.isStale(KEY, 1000L)).isTrue();
    }

    @Test
    @DisplayName("isStale：已推进版本 < 事件时间 → 新事件（放行）")
    void freshEvent() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn("1000");
        assertThat(helper.isStale(KEY, 2000L)).isFalse();
    }

    @Test
    @DisplayName("isStale：脏值 → 不拦截")
    void staleDirtyValue() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn("not-a-number");
        assertThat(helper.isStale(KEY, 1000L)).isFalse();
    }

    @Test
    @DisplayName("markDone：eventTime 缺失 → 不推进")
    void markDoneNullEventTime() {
        helper.markDone(KEY, null, 86400);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("markDone：执行单调推进脚本（只增不减）")
    void markDoneAdvances() {
        helper.markDone(KEY, 2000L, 86400);
        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(java.util.List.of(KEY)),
                eq("2000"), eq("86400"));
    }
}
