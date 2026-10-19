package com.myxhs.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.myxhs.search.config.IndexAliasManager;
import com.myxhs.search.service.ProductIndexDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重建目标解析（resolveTarget）决策矩阵单测（C-011 TC-10/TC-11 决策核心）
 * <p>
 * 验证口径：legacy 原地回退 / 别名模式建下一版本（status 记续传键）/ 中断续传（复用未完成目标，不重复建实体）。
 * 调用序（构建→切换→重放→删旧；切换前失败别名不动）在 doRebuild 中显式，见 report 走查记录。
 * </p>
 */
@SuppressWarnings("unchecked")
class IndexRebuildJobAliasTargetTest {

    private static final String ALIAS = "note_index";
    private static final String MAPPING = "{\"settings\":{\"number_of_replicas\":%REPLICAS%}}";
    private static final String STATUS_KEY = "myxhs:search:index:rebuild:status";

    private IndexAliasManager aliasManager;
    private HashOperations<String, Object, Object> hashOps;
    private IndexRebuildJob job;

    @BeforeEach
    void setUp() {
        aliasManager = mock(IndexAliasManager.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        job = new IndexRebuildJob(mock(ElasticsearchClient.class), mock(JdbcTemplate.class),
                redisTemplate, mock(RedissonClient.class), mock(ProductIndexDocumentBuilder.class), aliasManager);
    }

    private Object resolveTarget(String statusKey) {
        return ReflectionTestUtils.invokeMethod(job, "resolveTarget", ALIAS, MAPPING, statusKey);
    }

    private static Object field(Object target, String name) {
        return ReflectionTestUtils.invokeMethod(target, name);
    }

    @Test
    @DisplayName("legacy（同名实体无别名）→ 原地回退：write=current=alias，不建实体不写续传键")
    void legacyInPlace() throws Exception {
        when(aliasManager.resolve(ALIAS, MAPPING))
                .thenReturn(new IndexAliasManager.ResolvedIndex(ALIAS, true));

        Object target = resolveTarget("targetNoteIndex");

        assertThat(field(target, "writeIndex")).isEqualTo(ALIAS);
        assertThat(field(target, "currentIndex")).isEqualTo(ALIAS);
        assertThat(field(target, "inPlace")).isEqualTo(true);
        verify(aliasManager, never()).createNextIndex(anyString(), anyString(), anyString());
        verify(hashOps, never()).put(eq(STATUS_KEY), anyString(), anyString());
    }

    @Test
    @DisplayName("别名模式（无续传）→ 建下一版本 + status 记目标实体（TTL 1 天）")
    void aliasModeCreatesNext() throws Exception {
        when(aliasManager.resolve(ALIAS, MAPPING))
                .thenReturn(new IndexAliasManager.ResolvedIndex("note_index_v1", false));
        when(hashOps.get(STATUS_KEY, "targetNoteIndex")).thenReturn(null);
        when(aliasManager.createNextIndex(ALIAS, "note_index_v1", MAPPING)).thenReturn("note_index_v2");

        Object target = resolveTarget("targetNoteIndex");

        assertThat(field(target, "writeIndex")).isEqualTo("note_index_v2");
        assertThat(field(target, "currentIndex")).isEqualTo("note_index_v1");
        assertThat(field(target, "inPlace")).isEqualTo(false);
        verify(hashOps).put(STATUS_KEY, "targetNoteIndex", "note_index_v2");
    }

    @Test
    @DisplayName("中断续传：status 已有目标实体 → 复用（不重复建实体）")
    void resumeFromStatus() throws Exception {
        when(aliasManager.resolve(ALIAS, MAPPING))
                .thenReturn(new IndexAliasManager.ResolvedIndex("note_index_v1", false));
        when(hashOps.get(STATUS_KEY, "targetNoteIndex")).thenReturn("note_index_v2");

        Object target = resolveTarget("targetNoteIndex");

        assertThat(field(target, "writeIndex")).isEqualTo("note_index_v2");
        assertThat(field(target, "currentIndex")).isEqualTo("note_index_v1");
        verify(aliasManager, never()).createNextIndex(anyString(), anyString(), anyString());
    }
}
