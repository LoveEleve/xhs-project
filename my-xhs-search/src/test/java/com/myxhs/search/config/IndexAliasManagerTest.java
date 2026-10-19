package com.myxhs.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsAliasRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetAliasRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引别名管理器单测（C-011）
 * <p>
 * 验证口径：resolve 三分支（别名存在/legacy 同名实体/全新自动建账 v1）、
 * createNextIndex 版本递增、switchAlias 原子双动作、deleteIndex best-effort 吞异常。
 * </p>
 */
@SuppressWarnings("unchecked")
class IndexAliasManagerTest {

    private static final String ALIAS = "note_index";
    private static final String MAPPING = "{\"settings\":{\"number_of_replicas\":%REPLICAS%}}";

    private ElasticsearchIndicesClient indicesClient;
    private IndexAliasManager manager;

    @BeforeEach
    void setUp() {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        indicesClient = mock(ElasticsearchIndicesClient.class);
        when(esClient.indices()).thenReturn(indicesClient);
        manager = new IndexAliasManager(esClient);
        ReflectionTestUtils.setField(manager, "indexReplicas", 0);
    }

    private static IndexAliases alias() {
        return IndexAliases.of(a -> a.aliases(Map.of()));
    }

    @Test
    @DisplayName("resolve：别名存在 → 返回其指向实体，legacy=false")
    void resolveAliasExists() throws Exception {
        when(indicesClient.existsAlias(ArgumentMatchers.<Function<ExistsAliasRequest.Builder, ObjectBuilder<ExistsAliasRequest>>>any())).thenReturn(new BooleanResponse(true));
        when(indicesClient.getAlias(ArgumentMatchers.<Function<GetAliasRequest.Builder, ObjectBuilder<GetAliasRequest>>>any())).thenReturn(
                GetAliasResponse.of(b -> b.result(Map.of("note_index_v2", alias()))));

        IndexAliasManager.ResolvedIndex r = manager.resolve(ALIAS, MAPPING);

        assertThat(r.concreteIndex()).isEqualTo("note_index_v2");
        assertThat(r.legacyInPlace()).isFalse();
        verify(indicesClient, never()).create(ArgumentMatchers.<Function<CreateIndexRequest.Builder, ObjectBuilder<CreateIndexRequest>>>any());
    }

    @Test
    @DisplayName("resolve：无别名但同名实体存在（legacy 存量部署）→ 原地回退，legacy=true")
    void resolveLegacyInPlace() throws Exception {
        when(indicesClient.existsAlias(ArgumentMatchers.<Function<ExistsAliasRequest.Builder, ObjectBuilder<ExistsAliasRequest>>>any())).thenReturn(new BooleanResponse(false));
        when(indicesClient.exists(ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any())).thenReturn(new BooleanResponse(true));

        IndexAliasManager.ResolvedIndex r = manager.resolve(ALIAS, MAPPING);

        assertThat(r.concreteIndex()).isEqualTo(ALIAS);
        assertThat(r.legacyInPlace()).isTrue();
        verify(indicesClient, never()).create(ArgumentMatchers.<Function<CreateIndexRequest.Builder, ObjectBuilder<CreateIndexRequest>>>any());
    }

    @Test
    @DisplayName("resolve：全新环境（皆缺）→ 自动建账 v1 + 别名指向（%REPLICAS% 已替换）")
    void resolveFreshEnvAutoCreates() throws Exception {
        when(indicesClient.existsAlias(ArgumentMatchers.<Function<ExistsAliasRequest.Builder, ObjectBuilder<ExistsAliasRequest>>>any())).thenReturn(new BooleanResponse(false));
        when(indicesClient.exists(ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any())).thenReturn(new BooleanResponse(false));

        IndexAliasManager.ResolvedIndex r = manager.resolve(ALIAS, MAPPING);

        assertThat(r.concreteIndex()).isEqualTo("note_index_v1");
        assertThat(r.legacyInPlace()).isFalse();

        ArgumentCaptor<Function<CreateIndexRequest.Builder, ObjectBuilder<CreateIndexRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(indicesClient).create(captor.capture());
        CreateIndexRequest.Builder builder = new CreateIndexRequest.Builder();
        captor.getValue().apply(builder);
        CreateIndexRequest request = builder.build();
        assertThat(request.index()).isEqualTo("note_index_v1");
        verify(indicesClient).updateAliases(ArgumentMatchers.<Function<UpdateAliasesRequest.Builder, ObjectBuilder<UpdateAliasesRequest>>>any());
    }

    @Test
    @DisplayName("createNextIndex：v2 → v3（版本递增）")
    void createNextIndexIncrements() throws Exception {
        String next = manager.createNextIndex(ALIAS, "note_index_v2", MAPPING);

        assertThat(next).isEqualTo("note_index_v3");
        ArgumentCaptor<Function<CreateIndexRequest.Builder, ObjectBuilder<CreateIndexRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(indicesClient).create(captor.capture());
        CreateIndexRequest.Builder builder = new CreateIndexRequest.Builder();
        captor.getValue().apply(builder);
        assertThat(builder.build().index()).isEqualTo("note_index_v3");
    }

    @Test
    @DisplayName("createNextIndex：无 _v 后缀命名（异常输入）→ v1 起步（与当前实体不同名，不冲突）")
    void createNextIndexFromNonVersionedName() throws Exception {
        String next = manager.createNextIndex(ALIAS, "note_index", MAPPING);
        assertThat(next).isEqualTo("note_index_v1");
    }

    @DisplayName("createNextIndex：_v 后缀但非数字（异常输入）→ v2 起步，避免与当前实体同名")
    void createNextIndexFromUnparsableSuffix() throws Exception {
        String next = manager.createNextIndex(ALIAS, "note_index_vabc", MAPPING);
        assertThat(next).isEqualTo("note_index_v2");
    }

    @Test
    @DisplayName("switchAlias：单次 updateAliases 原子双动作（remove + add）")
    void switchAliasAtomic() throws Exception {
        manager.switchAlias(ALIAS, "note_index_v1", "note_index_v2");

        ArgumentCaptor<Function<UpdateAliasesRequest.Builder, ObjectBuilder<UpdateAliasesRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(indicesClient).updateAliases(captor.capture());
        UpdateAliasesRequest.Builder builder = new UpdateAliasesRequest.Builder();
        captor.getValue().apply(builder);
        UpdateAliasesRequest request = builder.build();
        assertThat(request.actions()).hasSize(2);
    }

    @Test
    @DisplayName("deleteIndex：删除失败仅告警不抛出（best-effort 不阻塞重建）")
    void deleteIndexSwallowsFailure() throws Exception {
        when(indicesClient.delete(ArgumentMatchers.<Function<co.elastic.clients.elasticsearch.indices.DeleteIndexRequest.Builder, ObjectBuilder<co.elastic.clients.elasticsearch.indices.DeleteIndexRequest>>>any()))
                .thenThrow(new RuntimeException("ES 抖动"));
        manager.deleteIndex("note_index_v1");
        verify(indicesClient).delete(ArgumentMatchers.<Function<co.elastic.clients.elasticsearch.indices.DeleteIndexRequest.Builder, ObjectBuilder<co.elastic.clients.elasticsearch.indices.DeleteIndexRequest>>>any());
    }
}
