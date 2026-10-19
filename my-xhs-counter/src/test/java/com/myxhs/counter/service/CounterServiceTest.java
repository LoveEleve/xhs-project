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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private com.myxhs.counter.mapper.LikeAuthorityMapper likeAuthorityMapper;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private Cursor<String> cursor;

    @Mock
    private CounterService counterService;

    @BeforeEach
    void setUp() {
        counterService = new CounterService(stringRedisTemplate, counterBuffer, counterMapper, likeAuthorityMapper);
    }

    // ==================== 计数 +1 ====================

    // ==================== 计数 -1 ====================

    // ==================== 查询计数 ====================

    @Test
    @DisplayName("查询计数 - Redis 命中直接返回")
    void getCounterSuccess() {
        String expectedKey = "myxhs:counter:1:{20001}:1";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(expectedKey)).thenReturn("42");

        long count = counterService.getCount(1, 20001L, 1);

        assertThat(count).isEqualTo(42L);
    }

    @Test
    @DisplayName("查询计数 - Redis 未命中回源 MySQL")
    void getCounterFallbackToMySQL() {
        String expectedKey = "myxhs:counter:1:{20001}:1";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(expectedKey)).thenReturn(null);

        Counter counter = new Counter();
        counter.setCountValue(10L);
        when(counterMapper.selectByTarget(1, 20001L, 1)).thenReturn(counter);

        long count = counterService.getCount(1, 20001L, 1);

        assertThat(count).isEqualTo(10L);
        // 回填 Redis（2026-09-21 改为单条 SET 带 TTL，防中间崩溃留下永不过期计数 key）
        verify(valueOperations).set(expectedKey, "10", java.time.Duration.ofDays(30));
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
    @DisplayName("Buffer 攒批 - dedup 路径增量后 Buffer 正确接收数据（刷盘链路连通）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void counterBufferFlush() {
        // C-009：原用已删除的 increment()（死路径）验证——改用生产活跃的 dedup 路径
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(1L, 5L));

        counterService.incrementWithDedup("msg-1", 1, 20001L, 1);

        // 验证 Buffer 正确接收了 +1 增量，保证后续定时/满量刷盘能持久化到 DB
        verify(counterBuffer).add(1, 20001L, 1, 1L);
    }

    // ==================== 对账修复：防清零守卫（2026-10-12，模块对照 07 §7-8） ====================

    @Test
    @DisplayName("对账修复 - analytics Set 缺失(SCARD=0)时跳过修正，不清零 Redis/DB")
    void reconcileLikeSkipsWhenAnalyticsSetMissing() {
        Cursor<String> cursor = mock(Cursor.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("myxhs:counter:1:{20001}:1");
        when(valueOperations.get("myxhs:counter:1:{20001}:1")).thenReturn("12");
        when(setOps.size("myxhs:like:note:{20001}")).thenReturn(0L);

        int fixed = counterService.reconcileLikeFromAnalytics();

        assertThat(fixed).isZero();
        verify(counterMapper, never()).updateCountValue(anyLong(), anyLong());
        verify(valueOperations, never()).set(eq("myxhs:counter:1:{20001}:1"), any(String.class));
    }

    @Test
    @DisplayName("对账修复 - analytics Set 存在且不一致时正常修正 Redis 与 DB")
    void reconcileLikeFixesWhenAnalyticsSetExists() {
        Cursor<String> cursor = mock(Cursor.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("myxhs:counter:1:{20001}:1");
        when(valueOperations.get("myxhs:counter:1:{20001}:1")).thenReturn("12");
        when(setOps.size("myxhs:like:note:{20001}")).thenReturn(15L);
        Counter dbRow = new Counter();
        dbRow.setId(55L);
        dbRow.setCountValue(12L);
        when(counterMapper.selectByBusinessKey(1, 20001L, 1)).thenReturn(dbRow);

        int fixed = counterService.reconcileLikeFromAnalytics();

        assertThat(fixed).isEqualTo(1);
        verify(valueOperations).set("myxhs:counter:1:{20001}:1", "15");
        verify(counterMapper).updateCountValue(55L, 15L);
    }

    // ==================== C-009 D2：like 权威对账（重建 + 集合复制） ====================

    @Test
    @DisplayName("C-009 D2-B：analytics 权威集缺失 + t_like 有行 → 重建集 + 修正值 + 复制 counter 集")
    void reconcile_rebuildsAuthorityFromDb() {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true).thenReturn(false);
        when(cursor.next()).thenReturn("myxhs:counter:1:{500}:1");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("myxhs:counter:1:{500}:1")).thenReturn("42");
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        // 权威集缺失（SCARD=0）→ 从 t_like 重建（2 个 liker）
        when(setOperations.size("myxhs:like:note:{500}")).thenReturn(0L);
        when(likeAuthorityMapper.selectUserIdsByBiz(1, 500L)).thenReturn(List.of(3001L, 3002L));
        Counter dbRow = new Counter();
        dbRow.setId(9L);
        dbRow.setCountValue(42L);
        when(counterMapper.selectByBusinessKey(1, 500L, 1)).thenReturn(dbRow);

        int fixed = counterService.reconcileLikeFromAnalytics();

        assertThat(fixed).isEqualTo(1);
        verify(setOperations).add(eq("myxhs:like:note:{500}"), eq("3001"), eq("3002"));
        verify(valueOperations).set("myxhs:counter:1:{500}:1", "2");
        // D2②：SUNIONSTORE 复制权威集到 counter 内部集
        verify(setOperations).unionAndStore(eq("myxhs:like:note:{500}"),
                eq(Collections.emptyList()), eq("myxhs:like:set:{500}:1"));
        verify(counterMapper).updateCountValue(9L, 2L);
    }

    @Test
    @DisplayName("C-009 D2②：权威集存在但值漂移 → 修正值 + 复制 counter 集（不重建）")
    void reconcile_copiesSetOnMismatch() {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true).thenReturn(false);
        when(cursor.next()).thenReturn("myxhs:counter:1:{500}:1");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("myxhs:counter:1:{500}:1")).thenReturn("42");
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size("myxhs:like:note:{500}")).thenReturn(2L);

        int fixed = counterService.reconcileLikeFromAnalytics();

        assertThat(fixed).isEqualTo(1);
        verify(likeAuthorityMapper, never()).selectUserIdsByBiz(any(), any());
        verify(setOperations).unionAndStore(eq("myxhs:like:note:{500}"),
                eq(Collections.emptyList()), eq("myxhs:like:set:{500}:1"));
    }
}
