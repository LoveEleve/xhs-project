package com.myxhs.common.id;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 号段模式 ID 生成器单元测试
 * <p>
 * 测试场景：
 * 1. 基本 ID 生成（连续递增）
 * 2. 并发安全（多线程无重复 ID）
 * 3. 号段切换（双 Buffer 交替）
 * 4. 乐观锁冲突重试
 * 5. 乐观锁冲突超过重试次数抛异常
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SegmentIdGeneratorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SegmentIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        idGenerator = new SegmentIdGenerator(jdbcTemplate);
    }

    @Test
    @DisplayName("基本 ID 生成 - 连续递增且在号段范围内")
    void testBasicIdGeneration() {
        // 模拟 DB 返回号段：maxId=0, step=1000, version=1
        mockDbSegment("user", 0L, 1000, 1);

        // 生成 10 个 ID
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(idGenerator.nextId("user"));
        }

        // 验证：连续递增，范围在 (0, 1000] 内
        assertThat(ids).hasSize(10);
        assertThat(ids).isSorted();
        assertThat(ids.get(0)).isEqualTo(1L);
        assertThat(ids.get(9)).isEqualTo(10L);
    }

    @Test
    @DisplayName("并发安全 - 多线程生成 ID 无重复")
    void testConcurrentIdGeneration() throws Exception {
        // 模拟 DB 返回号段：maxId=0, step=10000
        // 第一次加载 current，第二次加载 next（预加载触发时）
        AtomicInteger callCount = new AtomicInteger(0);
        when(jdbcTemplate.queryForMap(anyString(), eq("order")))
                .thenAnswer(invocation -> {
                    int count = callCount.getAndIncrement();
                    Map<String, Object> row = new HashMap<>();
                    row.put("max_id", (long) count * 10000);
                    row.put("step", 10000);
                    row.put("version", count + 1);
                    return row;
                });
        when(jdbcTemplate.update(anyString(), eq("order"), anyInt()))
                .thenReturn(1);

        // 20 个线程并发生成 ID
        int threadCount = 20;
        int idsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Set<Long> allIds = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        long id = idGenerator.nextId("order");
                        allIds.add(id);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证：所有 ID 唯一（无重复）
        int expectedTotal = threadCount * idsPerThread;
        assertThat(allIds).hasSize(expectedTotal);
    }

    @Test
    @DisplayName("号段切换 - current 用完后切换到 next")
    void testSegmentSwitch() {
        // 模拟：第一个号段 (0, 5]，第二个号段 (5, 10]
        AtomicInteger callCount = new AtomicInteger(0);
        when(jdbcTemplate.queryForMap(anyString(), eq("note")))
                .thenAnswer(invocation -> {
                    int count = callCount.getAndIncrement();
                    Map<String, Object> row = new HashMap<>();
                    row.put("max_id", (long) count * 5);
                    row.put("step", 5);
                    row.put("version", count + 1);
                    return row;
                });
        when(jdbcTemplate.update(anyString(), eq("note"), anyInt()))
                .thenReturn(1);

        // 生成 8 个 ID（超过第一个号段的 5 个）
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(idGenerator.nextId("note"));
        }

        // 验证：8 个 ID 全部唯一且递增
        assertThat(ids).hasSize(8);
        Set<Long> uniqueIds = new HashSet<>(ids);
        assertThat(uniqueIds).hasSize(8);
    }

    @Test
    @DisplayName("乐观锁冲突 - 重试后成功")
    void testOptimisticLockRetry() {
        // 模拟：前 2 次 update 返回 0（冲突），第 3 次返回 1（成功）
        when(jdbcTemplate.queryForMap(anyString(), eq("retry")))
                .thenReturn(createSegmentRow(0L, 1000, 1));
        when(jdbcTemplate.update(anyString(), eq("retry"), eq(1)))
                .thenReturn(0)  // 第 1 次冲突
                .thenReturn(0)  // 第 2 次冲突
                .thenReturn(1); // 第 3 次成功

        // 应该正常生成 ID（内部重试了 3 次）
        long id = idGenerator.nextId("retry");
        assertThat(id).isEqualTo(1L);
    }

    @Test
    @DisplayName("乐观锁冲突超过重试次数 - 抛出异常")
    void testOptimisticLockExhausted() {
        // 模拟：所有 update 都返回 0（冲突）
        when(jdbcTemplate.queryForMap(anyString(), eq("fail")))
                .thenReturn(createSegmentRow(0L, 1000, 1));
        when(jdbcTemplate.update(anyString(), eq("fail"), eq(1)))
                .thenReturn(0); // 始终冲突

        // 应该抛出异常
        assertThatThrownBy(() -> idGenerator.nextId("fail"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("号段分配失败");
    }

    @Test
    @DisplayName("不同 bizTag 独立号段")
    void testDifferentBizTagIndependent() {
        // 模拟两个不同的 bizTag
        when(jdbcTemplate.queryForMap(anyString(), eq("user")))
                .thenReturn(createSegmentRow(0L, 1000, 1));
        when(jdbcTemplate.update(anyString(), eq("user"), eq(1)))
                .thenReturn(1);

        when(jdbcTemplate.queryForMap(anyString(), eq("order")))
                .thenReturn(createSegmentRow(100L, 500, 1));
        when(jdbcTemplate.update(anyString(), eq("order"), eq(1)))
                .thenReturn(1);

        // 两个 bizTag 的 ID 独立
        long userId = idGenerator.nextId("user");
        long orderId = idGenerator.nextId("order");

        assertThat(userId).isEqualTo(1L);       // user: (0, 1000], 第一个是 1
        assertThat(orderId).isEqualTo(101L);    // order: (100, 600], 第一个是 101
    }

    // ==================== 辅助方法 ====================

    private void mockDbSegment(String bizTag, long maxId, int step, int version) {
        when(jdbcTemplate.queryForMap(anyString(), eq(bizTag)))
                .thenReturn(createSegmentRow(maxId, step, version));
        when(jdbcTemplate.update(anyString(), eq(bizTag), eq(version)))
                .thenReturn(1);
    }

    private Map<String, Object> createSegmentRow(long maxId, int step, int version) {
        Map<String, Object> row = new HashMap<>();
        row.put("max_id", maxId);
        row.put("step", step);
        row.put("version", version);
        return row;
    }
}
