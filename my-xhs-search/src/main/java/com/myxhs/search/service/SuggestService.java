package com.myxhs.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索建议服务
 * <p>
 * 基于 ES Completion Suggester 实现输入联想。
 * Completion Suggester 使用 FST（有限状态转换器）数据结构，
 * 性能比普通 prefix 查询高 10 倍+，专为自动补全设计。
 * </p>
 * <p>
 * 缓存策略：热门前缀的建议结果缓存到 Redis（1 小时 TTL），
 * 减少 ES 压力（搜索建议 QPS 极高，每输入一个字符触发一次）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestService {

    private final ElasticsearchClient esClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${search.suggest.index-name:suggest_index}")
    private String suggestIndexName;

    @Value("${search.suggest.max-suggestions:10}")
    private int maxSuggestions;

    @Value("${search.suggest.cache-ttl-seconds:3600}")
    private int cacheTtlSeconds;

    private static final String SUGGEST_CACHE_PREFIX = "myxhs:search:suggest:cache:";

    /**
     * 获取搜索建议
     * <p>
     * 流程：
     * 1. 先查 Redis 缓存（热门前缀命中率高）
     * 2. 缓存未命中 → 查 ES Completion Suggester
     * 3. 结果写入 Redis 缓存（1 小时 TTL）
     * </p>
     */
    public List<String> suggest(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Collections.emptyList();
        }
        prefix = prefix.trim();
        if (prefix.length() > 50) {
            prefix = prefix.substring(0, 50); // 防止超长前缀攻击
        }

        // 1. 查缓存（使用 MD5 摘要作为 Key 后缀，防止特殊字符和过长 Key）
        String keyHash = DigestUtils.md5DigestAsHex(prefix.getBytes(StandardCharsets.UTF_8));
        String cacheKey = SUGGEST_CACHE_PREFIX + keyHash;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSON.parseArray(cached, String.class);
        }

        // 2. 查 ES Completion Suggester
        List<String> suggestions = queryEsSuggester(prefix);

        // 3. 写缓存（空结果缓存较短时间防穿透，非空结果缓存正常 TTL）
        try {
            Duration ttl = suggestions.isEmpty()
                    ? Duration.ofMinutes(5)   // 空结果只缓存 5 分钟，新增热词后快速生效
                    : Duration.ofSeconds(cacheTtlSeconds);
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    JSON.toJSONString(suggestions),
                    ttl);
        } catch (Exception e) {
            log.warn("[搜索建议] 缓存写入失败: prefix={}", prefix);
        }

        return suggestions;
    }

    /**
     * 查询 ES Completion Suggester
     */
    private List<String> queryEsSuggester(String prefix) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(suggestIndexName)
                    .suggest(sg -> sg
                            .suggesters("keyword_suggest", fs -> fs
                                    .prefix(prefix)
                                    .completion(c -> c
                                            .field("keyword")
                                            .size(maxSuggestions)
                                            .skipDuplicates(true)))));

            SearchResponse<Map> response = esClient.search(request, Map.class);

            // 解析 Suggestion 结果
            Map<String, List<Suggestion<Map>>> suggestMap = response.suggest();
            if (suggestMap == null || !suggestMap.containsKey("keyword_suggest")) {
                return Collections.emptyList();
            }

            List<Suggestion<Map>> suggestions = suggestMap.get("keyword_suggest");
            if (suggestions == null || suggestions.isEmpty()) {
                return Collections.emptyList();
            }

            return suggestions.stream()
                    .flatMap(suggestion -> suggestion.completion().options().stream())
                    .map(CompletionSuggestOption::text)
                    .distinct()
                    .limit(maxSuggestions)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("[搜索建议] ES 查询失败: prefix={}", prefix, e);
            return Collections.emptyList();
        }
    }
}
