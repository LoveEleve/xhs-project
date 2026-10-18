package com.myxhs.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.search.dto.SellerOrderPageVO;
import com.myxhs.search.dto.SellerOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商家维度订单检索（C-004）
 * <p>
 * 检索形态：ES 单次查询（term sellerId + 可选 status/时间范围 + createdAt 倒序 + from/size 分页）——
 * 无 scatter、无 N+1。<b>ES 只做发现</b>；写操作/终态判断以 DB + CAS 为准（列表延迟不影响正确性）。
 * </p>
 * <p>
 * 深分页说明：from+size 受 ES 默认 max_result_window(10000) 限制；商家场景页码浅，
 * 深分页需 search_after（记录为演进）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerOrderSearchService {

    private final ElasticsearchClient esClient;

    @Value("${search.seller-order.index-name:seller_sub_order_index}")
    private String indexName;

    @Value("${search.seller-order.default-page-size:20}")
    private int defaultPageSize;

    @Value("${search.seller-order.max-page-size:100}")
    private int maxPageSize;

    /**
     * 商家子单列表查询。
     *
     * @param sellerId  商家 ID（必填——控制器守卫，防全量泄漏）
     * @param status    子单状态（可选）
     * @param startTime 下单时间起（可选，yyyy-MM-dd HH:mm:ss）
     * @param endTime   下单时间止（可选）
     */
    public SellerOrderPageVO search(Long sellerId, Integer status, String startTime, String endTime,
                                    Integer page, Integer size) {
        // 纵深防御：控制器已守卫；服务层再兜底（防未来直呼导致 NPE/全量语义）
        if (sellerId == null || sellerId < 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "sellerId 必填");
        }
        int pageNo = (page == null || page < 1) ? 1 : page;
        int pageSize = (size == null || size < 1) ? defaultPageSize : Math.min(size, maxPageSize);

        SearchRequest request = buildSearchRequest(sellerId, status, startTime, endTime, pageNo, pageSize);
        try {
            SearchResponse<Map> response = esClient.search(request, Map.class);

            List<SellerOrderVO> records = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source != null) {
                    records.add(toVO(source));
                }
            }
            long total = response.hits().total() != null ? response.hits().total().value() : records.size();

            SellerOrderPageVO result = new SellerOrderPageVO();
            result.setTotal(total);
            result.setPage(pageNo);
            result.setSize(pageSize);
            result.setRecords(records);
            return result;
        } catch (IOException | ElasticsearchException e) {
            log.error("[商家订单检索] ES 查询失败: sellerId={}, status={}", sellerId, status, e);
            throw new BizException(ResultCode.INTERNAL_ERROR, "商家订单查询失败，请稍后重试");
        }
    }

    /** 构造 ES 查询（包可见，供单测断言） */
    SearchRequest buildSearchRequest(Long sellerId, Integer status, String startTime, String endTime,
                                     int pageNo, int pageSize) {
        int from = (pageNo - 1) * pageSize;
        return SearchRequest.of(s -> {
            s.index(indexName).from(from).size(pageSize);
            s.query(q -> q.bool(b -> {
                // sellerId 必填（控制器/服务双层守卫）
                b.filter(f -> f.term(t -> t.field("sellerId").value(sellerId)));
                // 软删除标记过滤（deleted=true 为已删除）
                b.filter(f -> f.term(t -> t.field("deleted").value(false)));
                if (status != null) {
                    b.filter(f -> f.term(t -> t.field("status").value(status)));
                }
                boolean hasStart = startTime != null && !startTime.isBlank();
                boolean hasEnd = endTime != null && !endTime.isBlank();
                if (hasStart || hasEnd) {
                    b.filter(f -> f.range(r -> {
                        r.field("createdAt");
                        if (hasStart) {
                            r.gte(JsonData.of(startTime));
                        }
                        if (hasEnd) {
                            r.lte(JsonData.of(endTime));
                        }
                        return r;
                    }));
                }
                return b;
            }));
            // 最新下单在前
            s.sort(so -> so.field(f -> f.field("createdAt").order(SortOrder.Desc)));
            return s;
        });
    }

    /** 文档 → VO（包可见，供单测断言） */
    SellerOrderVO toVO(Map<String, Object> source) {
        SellerOrderVO vo = new SellerOrderVO();
        vo.setSubOrderNo((String) source.get("subOrderNo"));
        vo.setOrderNo((String) source.get("orderNo"));
        Object status = source.get("status");
        vo.setStatus(status instanceof Number n ? n.intValue() : null);
        vo.setTotalAmount(toBigDecimal(source.get("totalAmount")));
        vo.setDiscountAmount(toBigDecimal(source.get("discountAmount")));
        vo.setPayAmount(toBigDecimal(source.get("payAmount")));
        Object createdAt = source.get("createdAt");
        vo.setCreatedAt(createdAt == null ? null : String.valueOf(createdAt));
        return vo;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(value));
    }
}
