package com.myxhs.search.job;

import com.myxhs.common.response.R;
import com.myxhs.search.consumer.SellerSubOrderIndexWriter;
import com.myxhs.search.feign.OrderFeignClient;
import com.myxhs.search.service.SellerSubOrderIndexDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家子单索引存量回填 Job 单测（C-004 P1）
 * <p>
 * 覆盖：游标分页推进、断点续传（从断点开始）、整批成功才推进断点、
 * 写入失败抛错保留断点（可重入）、幂等（版本化 upsert 由 Writer 保证）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SellerSubOrderIndexBackfillJobTest {

    @Mock
    private OrderFeignClient orderFeignClient;
    @Mock
    private SellerSubOrderIndexWriter indexWriter;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private SellerSubOrderIndexBackfillJob job;

    @BeforeEach
    void setUp() {
        job = new SellerSubOrderIndexBackfillJob(orderFeignClient, indexWriter,
                new SellerSubOrderIndexDocumentBuilder(), stringRedisTemplate, redissonClient);
        ReflectionTestUtils.setField(job, "batchSize", 2);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
    }

    private Map<String, Object> row(long id, String subOrderNo) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("sub_order_no", subOrderNo);
        row.put("order_no", "ORDER-" + id);
        row.put("order_id", 1000 + id);
        row.put("user_id", 2001L);
        row.put("seller_id", 3001L);
        row.put("status", 2);
        row.put("total_amount", new BigDecimal("199.00"));
        row.put("discount_amount", new BigDecimal("9.00"));
        row.put("pay_amount", new BigDecimal("190.00"));
        row.put("created_at", LocalDateTime.of(2026, 10, 17, 12, 0, 0));
        row.put("updated_at", LocalDateTime.of(2026, 10, 17, 12, 30, 0));
        return row;
    }

    @Test
    @DisplayName("游标分页推进：满批继续、不满批收尾；逐行版本化 upsert；断点随批推进")
    void pagesThroughAndAdvancesCheckpoint() throws Exception {
        when(hashOps.get(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD))
                .thenReturn(null);
        when(orderFeignClient.subOrderScan(0L, 2))
                .thenReturn(R.ok(List.of(row(1L, "ORDER-1-1"), row(2L, "ORDER-2-1"))));
        when(orderFeignClient.subOrderScan(2L, 2))
                .thenReturn(R.ok(List.of(row(3L, "ORDER-3-1"))));

        int total = job.backfill();

        assertThat(total).isEqualTo(3);
        verify(indexWriter, times(3)).upsert(anyString(), anyMap(), anyLong());
        verify(hashOps).put(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD, "2");
        verify(hashOps).put(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD, "3");
    }

    @Test
    @DisplayName("断点续传：从既有断点开始（不重扫已回填部分）")
    void resumesFromCheckpoint() throws Exception {
        when(hashOps.get(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD))
                .thenReturn("2");
        when(orderFeignClient.subOrderScan(2L, 2)).thenReturn(R.ok(List.of(row(3L, "ORDER-3-1"))));

        job.backfill();

        verify(orderFeignClient).subOrderScan(2L, 2);
        verify(orderFeignClient, never()).subOrderScan(0L, 2);
        verify(indexWriter).upsert(eq("ORDER-3-1"), anyMap(), anyLong());
    }

    @Test
    @DisplayName("扫描失败：保留断点（已成功批次推进、失败批次不推进），可重入")
    void scanFailureKeepsCheckpoint() throws Exception {
        when(hashOps.get(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD))
                .thenReturn(null);
        when(orderFeignClient.subOrderScan(0L, 2))
                .thenReturn(R.ok(List.of(row(1L, "ORDER-1-1"), row(2L, "ORDER-2-1"))));
        when(orderFeignClient.subOrderScan(2L, 2)).thenReturn(R.fail(500, "order 不可用"));

        int total = job.backfill();

        assertThat(total).isEqualTo(2);
        verify(hashOps).put(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD, "2");
        verify(hashOps, never()).put(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD, "3");
    }

    @Test
    @DisplayName("写入失败：抛错且不推进断点（整批成功才推进——重入补齐）")
    void writeFailureThrowsAndKeepsCheckpoint() throws Exception {
        when(hashOps.get(SellerSubOrderIndexBackfillJob.STATUS_KEY, SellerSubOrderIndexBackfillJob.LAST_ID_FIELD))
                .thenReturn(null);
        when(orderFeignClient.subOrderScan(0L, 2))
                .thenReturn(R.ok(List.of(row(1L, "ORDER-1-1"), row(2L, "ORDER-2-1"))));
        doThrow(new IOException("es down")).when(indexWriter).upsert(eq("ORDER-2-1"), anyMap(), anyLong());

        assertThatThrownBy(() -> job.backfill())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回填写入失败");

        verify(hashOps, never()).put(anyString(), anyString(), anyString());
    }
}
