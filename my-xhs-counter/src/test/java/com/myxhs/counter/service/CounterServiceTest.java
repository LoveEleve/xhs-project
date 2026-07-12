package com.myxhs.counter.service;

import com.myxhs.counter.buffer.CounterBuffer;
import com.myxhs.counter.dto.CounterBatchRequest;
import com.myxhs.counter.entity.Counter;
import com.myxhs.counter.mapper.CounterMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CounterService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：StringRedisTemplate, ValueOperations, CounterBuffer, CounterMapper
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CounterServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CounterBuffer counterBuffer;

    @Mock
    private CounterMapper counterMapper;

    @Mock
    private com.myxhs.common.cache.RedisOperator redisOperator;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterService(redisOperator, stringRedisTemplate, counterBuffer, counterMapper);
    }

    // ==================== 计数 +1 ====================

    @Test
    @DisplayName("计数 +1 - Redis 自增后 Buffer 攒批写入")
    void incrementSuccess() {
        String expectedKey = "myxhs:counter:1:20001:1";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(expectedKey)).thenReturn(43L);

        counterService.increment(1, 20001L, 1);

        verify(valueOperations).increment(expectedKey);
        verify(counterBuffer).add(1, 20001L, 1, 1L);
    }

    // ==================== 计数 -1 ====================

    @Test
    @DisplayName("计数 -1 - Lua 脚本执行成功，Buffer 攒批写入")
    void decrementSuccess() {
        String expectedKey = "myxhs:counter:1:20001:2";
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(expectedKey))))
                .thenReturn(1L);

        boolean result = counterService.decrement(1, 20001L, 2);

        assertThat(result).isTrue();
        verify(counterBuffer).add(1, 20001L, 2, -1L);
    }

    @Test
    @DisplayName("计数 -1 - 归零保护拒绝扣减")
    void decrementZeroGuardRejects() {
        String expectedKey = "myxhs:counter:1:20001:2";
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(expectedKey))))
                .thenReturn(0L);

        boolean result = counterService.decrement(1, 20001L, 2);

        assertThat(result).isFalse();
    }

    // ==================== 查询计数 ====================

    @Test
    @DisplayName("查询计数 - Redis 命中直接返回")
    void getCounterSuccess() {
        String expectedKey = "myxhs:counter:1:20001:1";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(expectedKey)).thenReturn("42");

        long count = counterService.getCount(1, 20001L, 1);

        assertThat(count).isEqualTo(42L);
    }

    @Test
    @DisplayName("查询计数 - Redis 未命中回源 MySQL")
    void getCounterFallbackToMySQL() {
        String expectedKey = "myxhs:counter:1:20001:1";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(expectedKey)).thenReturn(null);

        Counter counter = new Counter();
        counter.setCountValue(10L);
        when(counterMapper.selectByTarget(1, 20001L, 1)).thenReturn(counter);

        long count = counterService.getCount(1, 20001L, 1);

        assertThat(count).isEqualTo(10L);
        // 回填 Redis
        verify(valueOperations).set(expectedKey, "10");
    }

    // ==================== 批量查询 ====================

    @Test
    @DisplayName("批量查询 - Redis Pipeline 全命中")
    void batchGetSuccess() {
        CounterBatchRequest.QueryItem item1 = new CounterBatchRequest.QueryItem(1, 20001L, List.of(1));
        CounterBatchRequest.QueryItem item2 = new CounterBatchRequest.QueryItem(1, 20002L, List.of(2));
        CounterBatchRequest request = new CounterBatchRequest(List.of(item1, item2));

        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList("42", "18"));

        Map<String, Map<String, Long>> result = counterService.batchGetCounts(request);

        assertThat(result).hasSize(2);
        assertThat(result.get("1:20001")).containsEntry("like", 42L);
        assertThat(result.get("1:20002")).containsEntry("collect", 18L);
    }

    @Test
    @DisplayName("批量查询 - 空查询列表返回空结果")
    void batchGetEmptyRequest() {
        CounterBatchRequest request = new CounterBatchRequest(Collections.emptyList());

        Map<String, Map<String, Long>> result = counterService.batchGetCounts(request);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("批量查询 - null 查询列表返回空结果")
    void batchGetNullRequest() {
        CounterBatchRequest request = new CounterBatchRequest(null);

        Map<String, Map<String, Long>> result = counterService.batchGetCounts(request);

        assertThat(result).isEmpty();
    }

    // ==================== Buffer 攒批机制 ====================

    @Test
    @DisplayName("Buffer 攒批 - 增量操作后 Buffer 正确接收数据，确保刷盘链路连通")
    void counterBufferFlush() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("myxhs:counter:1:20001:1")).thenReturn(100L);

        counterService.increment(1, 20001L, 1);

        // 验证 Buffer 正确接收了 +1 增量，保证后续定时/满量刷盘能持久化到 DB
        verify(counterBuffer).add(1, 20001L, 1, 1L);

        // 验证 Redis 也正确自增
        verify(valueOperations).increment("myxhs:counter:1:20001:1");
    }
}
