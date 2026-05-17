package com.myxhs.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.alibaba.fastjson2.JSON;
import com.myxhs.search.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品搜索服务
 * <p>
 * 核心职责：
 * 1. 基于 ES 8.x Java Client 构建商品全文搜索
 * 2. 支持关键词 + 分类筛选 + 价格区间 + 多维排序 + Search After + 高亮
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService extends AbstractSearchService {

    private final ElasticsearchClient esClient;

    @Value("${search.product.index-name:product_index}")
    private String productIndexName;

    @Value("${search.product.default-page-size:20}")
    private int defaultPageSize;

    @Value("${search.product.max-page-size:50}")
    private int maxPageSize;

    /**
     * 商品搜索
     * <p>
     * 查询策略：
     * - match: name 字段全文搜索
     * - filter: status=1 + categoryId + price range
     * - 排序: relevance / price_asc / price_desc / sales
     * - 分页: Search After
     * - 高亮: name 字段
     * </p>
     */
    public SearchResultVO<ProductSearchVO> searchProducts(ProductSearchRequest request) {
        int size = normalizeSize(request.getSize(), defaultPageSize, maxPageSize);

        try {
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index(productIndexName)
                    .size(size);

            // 构建查询
            searchBuilder.query(buildProductQuery(request));

            // 排序
            applyProductSorting(searchBuilder, request.getSort());

            // Search After
            if (request.getSearchAfter() != null && !request.getSearchAfter().isBlank()) {
                List<FieldValue> sortValues = parseSearchAfter(request.getSearchAfter());
                searchBuilder.searchAfter(sortValues);
            }

            // 高亮
            searchBuilder.highlight(h -> h
                    .fields("name", HighlightField.of(hf -> hf.preTags("<em>").postTags("</em>"))));

            SearchResponse<Map> response = esClient.search(searchBuilder.build(), Map.class);

            return buildProductResult(response, size);

        } catch (Exception e) {
            log.error("[商品搜索] 查询失败: keyword={}", request.getKeyword(), e);
            return SearchResultVO.<ProductSearchVO>builder()
                    .items(Collections.emptyList())
                    .total(0)
                    .hasMore(false)
                    .took(0)
                    .build();
        }
    }

    /**
     * 构建商品搜索查询
     */
    private Query buildProductQuery(ProductSearchRequest request) {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // must: 关键词搜索
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            boolBuilder.must(q -> q.match(m -> m
                    .field("name")
                    .query(request.getKeyword())
                    .analyzer("ik_smart")));
        } else {
            boolBuilder.must(q -> q.matchAll(m -> m));
        }

        // filter: 只搜上架商品
        boolBuilder.filter(f -> f.term(t -> t.field("status").value(1)));

        // filter: 分类筛选
        if (request.getCategoryId() != null) {
            boolBuilder.filter(f -> f.term(t -> t.field("categoryId").value(request.getCategoryId())));
        }

        // filter: 价格区间
        if (request.getMinPrice() != null || request.getMaxPrice() != null) {
            boolBuilder.filter(f -> f.range(r -> {
                var rangeBuilder = r.field("price");
                if (request.getMinPrice() != null) {
                    rangeBuilder.gte(JsonData.of(request.getMinPrice().doubleValue()));
                }
                if (request.getMaxPrice() != null) {
                    rangeBuilder.lte(JsonData.of(request.getMaxPrice().doubleValue()));
                }
                return rangeBuilder;
            }));
        }

        return Query.of(q -> q.bool(boolBuilder.build()));
    }

    /**
     * 应用商品排序策略
     */
    private void applyProductSorting(SearchRequest.Builder builder, String sort) {
        if (sort == null) sort = "relevance";

        switch (sort) {
            case "price_asc" -> builder
                    .sort(s -> s.field(f -> f.field("price").order(SortOrder.Asc)))
                    .sort(s -> s.field(f -> f.field("spuId").order(SortOrder.Asc)));
            case "price_desc" -> builder
                    .sort(s -> s.field(f -> f.field("price").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("spuId").order(SortOrder.Desc)));
            case "sales" -> builder
                    .sort(s -> s.field(f -> f.field("sales").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("spuId").order(SortOrder.Desc)));
            default -> builder
                    .sort(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("spuId").order(SortOrder.Desc)));
        }
    }

    /**
     * 构建商品搜索结果
     */
    @SuppressWarnings("unchecked")
    private SearchResultVO<ProductSearchVO> buildProductResult(SearchResponse<Map> response, int size) {
        List<ProductSearchVO> items = new ArrayList<>();
        String lastSearchAfter = null;

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) continue;

            ProductSearchVO vo = ProductSearchVO.builder()
                    .spuId(toLong(source.get("spuId")))
                    .skuId(toLong(source.get("skuId")))
                    .name((String) source.get("name"))
                    .categoryId(toLong(source.get("categoryId")))
                    .categoryName((String) source.get("categoryName"))
                    .brandName((String) source.get("brandName"))
                    .price(toBigDecimal(source.get("price")))
                    .image((String) source.get("image"))
                    .sales(toLong(source.get("sales")))
                    .createdAt(source.get("createdAt") != null ? source.get("createdAt").toString() : null)
                    .build();

            // 高亮
            if (hit.highlight() != null) {
                List<String> nameHighlight = hit.highlight().get("name");
                if (nameHighlight != null && !nameHighlight.isEmpty()) {
                    vo.setHighlightName(nameHighlight.get(0));
                }
            }

            items.add(vo);

            if (hit.sort() != null && !hit.sort().isEmpty()) {
                lastSearchAfter = JSON.toJSONString(hit.sort());
            }
        }

        long total = response.hits().total() != null ? response.hits().total().value() : 0;

        return SearchResultVO.<ProductSearchVO>builder()
                .items(items)
                .total(total)
                .searchAfter(lastSearchAfter)
                .hasMore(items.size() >= size)
                .took(response.took())
                .build();
    }

}
