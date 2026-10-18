package com.myxhs.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.myxhs.common.exception.BizException;
import com.myxhs.search.dto.SellerOrderPageVO;
import com.myxhs.search.dto.SellerOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 商家订单检索服务单测（C-004）
 * <p>
 * 覆盖：查询构造（sellerId/deleted 必带过滤 + 可选 status/时间范围 + 排序/分页）、
 * 文档→VO 映射、ES 异常降级。ES 客户端以 mock 替代。
 * </p>
 */
class SellerOrderSearchServiceTest {

    private static final String INDEX = "seller_sub_order_index";

    private ElasticsearchClient esClient;
    private SellerOrderSearchService service;

    @BeforeEach
    void setUp() {
        esClient = mock(ElasticsearchClient.class);
        service = new SellerOrderSearchService(esClient);
        ReflectionTestUtils.setField(service, "indexName", INDEX);
        ReflectionTestUtils.setField(service, "defaultPageSize", 20);
        ReflectionTestUtils.setField(service, "maxPageSize", 100);
    }

    private List<String> termFields(List<Query> queries) {
        List<String> fields = new ArrayList<>();
        for (Query q : queries) {
            if (q.isTerm()) {
                fields.add(q.term().field());
            }
        }
        return fields;
    }

    @Test
    @DisplayName("查询构造（基础）：sellerId + deleted 必带过滤；createdAt 倒序；from/size 分页")
    void buildSearchRequestBasic() {
        SearchRequest request = service.buildSearchRequest(3001L, null, null, null, 1, 20);

        assertThat(request.index()).containsExactly(INDEX);
        assertThat(request.from()).isEqualTo(0);
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.sort()).hasSize(1);

        List<Query> filters = request.query().bool().filter();
        assertThat(filters).hasSize(2);
        assertThat(termFields(filters)).containsExactlyInAnyOrder("sellerId", "deleted");
    }

    @Test
    @DisplayName("查询构造（全条件）：status + 时间范围（range createdAt）+ 第二页")
    void buildSearchRequestWithFilters() {
        SearchRequest request = service.buildSearchRequest(3001L, 2,
                "2026-10-01 00:00:00", "2026-10-17 23:59:59", 2, 10);

        assertThat(request.from()).isEqualTo(10);
        assertThat(request.size()).isEqualTo(10);

        List<Query> filters = request.query().bool().filter();
        assertThat(filters).hasSize(4);
        assertThat(termFields(filters)).containsExactlyInAnyOrder("sellerId", "deleted", "status");
        boolean hasRange = filters.stream().anyMatch(Query::isRange);
        assertThat(hasRange).isTrue();
    }

    @Test
    @DisplayName("sellerId=0（平台自营）为合法查询条件")
    void sellerIdZeroIsValid() {
        SearchRequest request = service.buildSearchRequest(0L, null, null, null, 1, 20);
        assertThat(termFields(request.query().bool().filter())).contains("sellerId");
    }

    @Test
    @DisplayName("服务层纵深守卫：sellerId 为 null/负 → BizException（不触达 ES）")
    void sellerIdGuardAtServiceLevel() {
        assertThatThrownBy(() -> service.search(null, null, null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sellerId 必填");
        assertThatThrownBy(() -> service.search(-1L, null, null, null, 1, 20))
                .isInstanceOf(BizException.class);
        org.mockito.Mockito.verifyNoInteractions(esClient);
    }

    @Test
    @DisplayName("文档 → VO 映射（金额/状态/时间）")
    void mapsDocumentToVO() {
        Map<String, Object> source = new HashMap<>();
        source.put("subOrderNo", "ORDER-1001-1");
        source.put("orderNo", "ORDER-1001");
        source.put("status", 2);
        source.put("totalAmount", 199.0);
        source.put("discountAmount", 9.0);
        source.put("payAmount", 190.0);
        source.put("createdAt", "2026-10-17 12:00:00");

        SellerOrderVO vo = service.toVO(source);

        assertThat(vo.getSubOrderNo()).isEqualTo("ORDER-1001-1");
        assertThat(vo.getOrderNo()).isEqualTo("ORDER-1001");
        assertThat(vo.getStatus()).isEqualTo(2);
        assertThat(vo.getTotalAmount()).isEqualByComparingTo(new BigDecimal("199.0"));
        assertThat(vo.getPayAmount()).isEqualByComparingTo(new BigDecimal("190.0"));
        assertThat(vo.getCreatedAt()).isEqualTo("2026-10-17 12:00:00");
    }

    @Test
    @DisplayName("检索流程：ES 命中 → 分页结果（total/records）")
    void searchMapsHits() throws Exception {
        Map<String, Object> source1 = new HashMap<>();
        source1.put("subOrderNo", "ORDER-1-1");
        source1.put("orderNo", "ORDER-1");
        source1.put("status", 2);
        source1.put("payAmount", 190.0);
        source1.put("createdAt", "2026-10-17 12:00:00");

        Map<String, Object> source2 = new HashMap<>();
        source2.put("subOrderNo", "ORDER-2-1");
        source2.put("status", 1);

        SearchResponse<Map> response = SearchResponse.of(b -> b
                .took(1)
                .timedOut(false)
                .shards(sh -> sh.total(1L).successful(1L).failed(0L))
                .hits(h -> h
                        .total(t -> t.value(2L).relation(TotalHitsRelation.Eq))
                        .hits(
                                Hit.of(hit -> hit.index(INDEX).id("ORDER-1-1").source(source1)),
                                Hit.of(hit -> hit.index(INDEX).id("ORDER-2-1").source(source2)))));
        doReturn(response).when(esClient).search(any(SearchRequest.class), eq(Map.class));

        SellerOrderPageVO result = service.search(3001L, null, null, null, 1, 20);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).getSubOrderNo()).isEqualTo("ORDER-1-1");
        assertThat(result.getRecords().get(1).getSubOrderNo()).isEqualTo("ORDER-2-1");
    }

    @Test
    @DisplayName("ES 异常 → BizException 降级（列表报错；不影响任何写路径）")
    void esFailureThrowsBizException() throws Exception {
        doThrow(new IOException("ES down")).when(esClient).search(any(SearchRequest.class), eq(Map.class));

        assertThatThrownBy(() -> service.search(3001L, null, null, null, 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商家订单查询失败");
    }

    @Test
    @DisplayName("分页参数兜底：page<1 → 1；size<1 → 默认；size 超上限 → 截断（search 层）")
    void paginationDefaultsAndCap() throws Exception {
        SearchResponse<Map> response = SearchResponse.of(b -> b
                .took(1)
                .timedOut(false)
                .shards(sh -> sh.total(1L).successful(1L).failed(0L))
                .hits(h -> h
                        .total(t -> t.value(0L).relation(TotalHitsRelation.Eq))
                        .hits(java.util.Collections.<Hit<Map>>emptyList())));
        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        doReturn(response).when(esClient).search(captor.capture(), eq(Map.class));

        SellerOrderPageVO page = service.search(3001L, null, null, null, 0, 0);
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20); // default-page-size
        assertThat(captor.getValue().from()).isEqualTo(0);
        assertThat(captor.getValue().size()).isEqualTo(20);

        service.search(3001L, null, null, null, 1, 500);
        assertThat(captor.getValue().size()).isEqualTo(100); // max-page-size 截断
    }
}
