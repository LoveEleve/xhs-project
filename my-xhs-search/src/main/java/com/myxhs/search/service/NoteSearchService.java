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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 笔记搜索服务
 * <p>
 * 核心职责：
 * 1. 基于 ES 8.x Java Client 构建全文搜索查询
 * 2. 支持 multi_match（标题权重 3x）+ filter + 排序 + Search After 深分页 + 高亮
 * 3. 搜索时自动记录搜索历史到 Redis
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteSearchService extends AbstractSearchService {

    private final ElasticsearchClient esClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Value("${search.note.default-page-size:20}")
    private int defaultPageSize;

    @Value("${search.note.max-page-size:50}")
    private int maxPageSize;

    /**
     * 笔记全文搜索
     * <p>
     * 查询策略：
     * - multi_match: title(权重3) + content(权重1)，使用 ik_smart 分词
     * - filter: status=1（已发布）
     * - 排序: relevance(_score) / time(createdAt) / hot(likeCount)
     * - 分页: Search After（游标分页，O(1) 性能）
     * - 高亮: title + content 字段
     * </p>
     */
    public SearchResultVO<NoteSearchVO> searchNotes(NoteSearchRequest request, Long userId) {
        int size = normalizeSize(request.getSize(), defaultPageSize, maxPageSize);

        try {
            // 记录搜索历史（异步，不影响搜索性能）
            if (userId != null && request.getKeyword() != null && !request.getKeyword().isBlank()) {
                recordSearchHistory(userId, request.getKeyword());
            }

            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index(noteIndexName)
                    .size(size);

            // 构建查询
            searchBuilder.query(buildNoteQuery(request));

            // 排序
            applySorting(searchBuilder, request.getSort());

            // Search After 深分页
            if (request.getSearchAfter() != null && !request.getSearchAfter().isBlank()) {
                List<FieldValue> sortValues = parseSearchAfter(request.getSearchAfter());
                searchBuilder.searchAfter(sortValues);
            }

            // 高亮
            searchBuilder.highlight(h -> h
                    .fields("title", HighlightField.of(hf -> hf.preTags("<em>").postTags("</em>")))
                    .fields("content", HighlightField.of(hf -> hf
                            .preTags("<em>").postTags("</em>")
                            .fragmentSize(150).numberOfFragments(1))));

            SearchResponse<Map> response = esClient.search(searchBuilder.build(), Map.class);

            return buildNoteResult(response, size);

        } catch (Exception e) {
            log.error("[笔记搜索] 查询失败: keyword={}", request.getKeyword(), e);
            return SearchResultVO.<NoteSearchVO>builder()
                    .items(Collections.emptyList())
                    .total(0)
                    .hasMore(false)
                    .took(0)
                    .build();
        }
    }

    /**
     * 构建笔记搜索查询
     */
    private Query buildNoteQuery(NoteSearchRequest request) {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // must: multi_match 全文搜索（标题权重 3 倍）
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            boolBuilder.must(q -> q.multiMatch(mm -> mm
                    .query(request.getKeyword())
                    .fields("title^3", "content")
                    .analyzer("ik_smart")));
        } else {
            // 空关键词：match_all
            boolBuilder.must(q -> q.matchAll(m -> m));
        }

        // filter: 只搜已发布的笔记（status=1）
        boolBuilder.filter(f -> f.term(t -> t.field("status").value(1)));

        return Query.of(q -> q.bool(boolBuilder.build()));
    }

    /**
     * 应用排序策略
     */
    private void applySorting(SearchRequest.Builder builder, String sort) {
        if (sort == null) sort = "relevance";

        switch (sort) {
            case "time" -> builder
                    .sort(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("noteId").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("_id").order(SortOrder.Asc)));
            case "hot" -> builder
                    .sort(s -> s.field(f -> f.field("likeCount").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("noteId").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("_id").order(SortOrder.Asc)));
            default -> builder // relevance: 按相关度排序
                    .sort(s -> s.score(sc -> sc.order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("noteId").order(SortOrder.Desc)))
                    .sort(s -> s.field(f -> f.field("_id").order(SortOrder.Asc)));
        }
    }

    /**
     * 构建笔记搜索结果
     */
    @SuppressWarnings("unchecked")
    private SearchResultVO<NoteSearchVO> buildNoteResult(SearchResponse<Map> response, int size) {
        List<NoteSearchVO> items = new ArrayList<>();
        String lastSearchAfter = null;

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) continue;

            NoteSearchVO vo = NoteSearchVO.builder()
                    .noteId(toLong(source.get("noteId")))
                    .userId(toLong(source.get("userId")))
                    .title((String) source.get("title"))
                    .content((String) source.get("content"))
                    .coverImage((String) source.get("coverImage"))
                    .likeCount(toLong(source.get("likeCount")))
                    .collectCount(toLong(source.get("collectCount")))
                    .commentCount(toLong(source.get("commentCount")))
                    .createdAt(source.get("createdAt") != null ? source.get("createdAt").toString() : null)
                    .build();

            // 高亮替换
            if (hit.highlight() != null) {
                List<String> titleHighlight = hit.highlight().get("title");
                if (titleHighlight != null && !titleHighlight.isEmpty()) {
                    vo.setHighlightTitle(titleHighlight.get(0));
                }
                List<String> contentHighlight = hit.highlight().get("content");
                if (contentHighlight != null && !contentHighlight.isEmpty()) {
                    vo.setHighlightContent(contentHighlight.get(0));
                }
            }

            items.add(vo);

            // 记录最后一条的 sort values（用于下一页 Search After）
            if (hit.sort() != null && !hit.sort().isEmpty()) {
                lastSearchAfter = JSON.toJSONString(hit.sort());
            }
        }

        long total = response.hits().total() != null ? response.hits().total().value() : 0;

        return SearchResultVO.<NoteSearchVO>builder()
                .items(items)
                .total(total)
                .searchAfter(lastSearchAfter)
                .hasMore(items.size() >= size)
                .took(response.took())
                .build();
    }

    /**
     * 记录搜索历史到 Redis（Lua 脚本原子操作）
     * <p>
     * 使用 Lua 脚本保证 LREM + LPUSH + LTRIM + EXPIRE 四步操作的原子性，
     * 避免分布式多实例并发场景下出现重复关键词或列表超长问题。
     * </p>
     */
    private static final DefaultRedisScript<Long> RECORD_HISTORY_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('LREM', KEYS[1], 0, ARGV[1])
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """, Long.class);

    private void recordSearchHistory(Long userId, String keyword) {
        try {
            String key = "myxhs:search:history:" + userId;
            stringRedisTemplate.execute(
                    RECORD_HISTORY_SCRIPT,
                    List.of(key),
                    keyword,
                    "20",                          // 最大保留条数
                    String.valueOf(30 * 24 * 3600) // 30 天过期（秒）
            );
        } catch (Exception e) {
            log.warn("[搜索历史] 记录失败: userId={}, keyword={}", userId, keyword);
        }
    }

}
