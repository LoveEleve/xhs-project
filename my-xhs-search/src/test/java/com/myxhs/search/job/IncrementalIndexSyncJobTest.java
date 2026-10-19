package com.myxhs.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.search.service.ProductIndexDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 增量补偿 Job 失败集收敛矩阵单测（C-011 R1/R5）
 * <p>
 * 验证口径（bulk 逐项判定，private 方法经反射直测）：
 * <ul>
 *   <li>version_conflict 豁免：ES 已是更新版本 → 不计失败（原实现计失败 → 失败集永久循环）；</li>
 *   <li>真失败（如 mapper_parsing）→ 回填失败集（下轮重试）；</li>
 *   <li>商品文档构建失败 = 确定性失败 → 丢弃 + doc_build_failed 指标，不回填（防每分钟必败循环）；</li>
 *   <li>整批异常 → 保守全量回填（不丢）。</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class IncrementalIndexSyncJobTest {

    private static final String NOTE_INDEX = "note_index";
    private static final String PRODUCT_INDEX = "product_index";

    private ElasticsearchClient esClient;
    private ProductIndexDocumentBuilder productBuilder;
    private BusinessMetrics businessMetrics;
    private IncrementalIndexSyncJob job;

    @BeforeEach
    void setUp() {
        esClient = mock(ElasticsearchClient.class);
        productBuilder = mock(ProductIndexDocumentBuilder.class);
        businessMetrics = mock(BusinessMetrics.class);
        job = new IncrementalIndexSyncJob(mock(StringRedisTemplate.class), esClient,
                mock(JdbcTemplate.class), productBuilder, businessMetrics);
        ReflectionTestUtils.setField(job, "noteIndexName", NOTE_INDEX);
        ReflectionTestUtils.setField(job, "productIndexName", PRODUCT_INDEX);
    }

    private static BulkResponseItem item(String id, String errorType) {
        return BulkResponseItem.of(i -> {
            i.operationType(OperationType.Index).id(id).index(NOTE_INDEX).status(errorType == null ? 200 : 500);
            if (errorType != null) {
                i.error(ErrorCause.of(e -> e.type(errorType).reason("boom")));
            }
            return i;
        });
    }

    private static Map<String, Object> note(long id) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("updated_at", 1715510539000L);
        return m;
    }

    private static Map<String, Object> product(long id) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("updated_at", 1715510539000L);
        return m;
    }

    @Test
    @DisplayName("R1 收敛矩阵：version_conflict 豁免 + 真失败回填（笔记）")
    void noteBulkConvergenceMatrix() throws IOException {
        when(esClient.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class)))
                .thenReturn(BulkResponse.of(b -> b.took(1).errors(true).items(List.of(
                        item("1", null),
                        item("2", "version_conflict_engine_exception"),
                        item("3", "mapper_parsing_exception")))));

        Set<Long> failed = ReflectionTestUtils.invokeMethod(job, "bulkIndexNotes",
                List.of(note(1L), note(2L), note(3L)));

        assertThat(failed).containsExactly(3L);
    }

    @Test
    @DisplayName("R1：全部 conflict → 失败集为空（不再永久循环重投）")
    void noteBulkAllConflictsConverge() throws IOException {
        when(esClient.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class)))
                .thenReturn(BulkResponse.of(b -> b.took(1).errors(true).items(List.of(
                        item("1", "version_conflict_engine_exception"),
                        item("2", "version_conflict_engine_exception")))));

        Set<Long> failed = ReflectionTestUtils.invokeMethod(job, "bulkIndexNotes",
                List.of(note(1L), note(2L)));

        assertThat(failed).isEmpty();
    }

    @Test
    @DisplayName("R5：商品文档构建失败=确定性失败 → 丢弃+指标，不回填；conflict 同样豁免")
    void productBulkDeterministicFailureDiscarded() throws IOException {
        when(productBuilder.build(anyMap(), anyMap()))
                .thenReturn(Map.of("spuId", 10L))
                .thenThrow(new IllegalStateException("images 解析失败"))
                .thenReturn(Map.of("spuId", 12L));
        when(esClient.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class)))
                .thenReturn(BulkResponse.of(b -> b.took(1).errors(true).items(List.of(
                        item("10", null),
                        item("12", "version_conflict_engine_exception")))));

        Set<Long> failed = ReflectionTestUtils.invokeMethod(job, "bulkIndexProducts",
                List.of(product(10L), product(11L), product(12L)));

        assertThat(failed).isEmpty();
        verify(businessMetrics).recordAction("search", "doc_build_failed");
    }

    @Test
    @DisplayName("整批异常 → 保守全量回填（不丢消息）")
    void bulkExceptionFallsBackToFullRetrySet() throws IOException {
        when(esClient.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class)))
                .thenThrow(new IOException("ES 不可用"));

        Set<Long> failed = ReflectionTestUtils.invokeMethod(job, "bulkIndexNotes",
                List.of(note(1L), note(2L)));

        assertThat(failed).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("商品整批异常 → 全量回填（构建失败项除外语义在单测1覆盖，此处验证整批兜底）")
    void productBulkExceptionFallsBack() throws IOException {
        when(productBuilder.build(anyMap(), anyMap())).thenReturn(Map.of("spuId", 1L));
        when(esClient.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class)))
                .thenThrow(new IOException("ES 不可用"));

        Set<Long> failed = ReflectionTestUtils.invokeMethod(job, "bulkIndexProducts",
                List.of(product(1L), product(2L)));

        assertThat(failed).containsExactlyInAnyOrder(1L, 2L);
    }
}
