package com.myxhs.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 知识索引（ES BM25；D12 v1 不做分块/向量；评测触发后再加 knn）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexer {

    public static final String INDEX = "xhs_ai_knowledge";

    private final KnowledgeRepository knowledgeRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${MYXHS_ES_URL:}")
    private String esUrl;

    @Value("${MYXHS_ES_USER:}")
    private String esUser;

    @Value("${MYXHS_ES_PASSWORD:}")
    private String esPassword;

    private volatile RestClient client;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(this::reindex);
    }

    private RestClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    String auth = Base64.getEncoder()
                            .encodeToString((esUser + ":" + esPassword).getBytes());
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(2000);
                    factory.setReadTimeout(8000);
                    client = RestClient.builder()
                            .baseUrl(esUrl)
                            .requestFactory(factory)
                            .defaultHeader("Authorization", "Basic " + auth)
                            .build();
                }
            }
        }
        return client;
    }

    /** 幂等重建索引（启动异步执行 / 管理端手动触发） */
    public Map<String, Object> reindex() {
        try {
            ensureIndex();
            List<KnowledgeCard> cards = knowledgeRepository.all();
            int ok = 0;
            for (KnowledgeCard card : cards) {
                try {
                    client().put()
                            .uri("/" + INDEX + "/_doc/" + encodeId(card.id()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsString(toDoc(card)))
                            .retrieve()
                            .toBodilessEntity();
                    ok++;
                } catch (Exception e) {
                    log.warn("[知识索引] 单卡失败 id={}: {}", card.id(), e.getMessage());
                }
            }
            client().post().uri("/" + INDEX + "/_refresh").retrieve().toBodilessEntity();
            long total = count();
            log.info("[知识索引] 完成: indexed={}, indexed_now={}", total, ok);
            return Map.of("cards", cards.size(), "indexed", total, "ok", ok);
        } catch (Exception e) {
            log.error("[知识索引] 重建失败: {}", e.getMessage());
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    private void ensureIndex() throws Exception {
        try {
            client().head().uri("/" + INDEX).retrieve().toBodilessEntity();
        } catch (Exception notFound) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("id", Map.of("type", "keyword"));
            properties.put("layer", Map.of("type", "keyword"));
            properties.put("path", Map.of("type", "keyword"));
            properties.put("category", Map.of("type", "keyword"));
            properties.put("priority", Map.of("type", "keyword"));
            properties.put("source_path", Map.of("type", "keyword"));
            properties.put("title", Map.of("type", "text"));
            properties.put("question", Map.of("type", "text"));
            properties.put("answer", Map.of("type", "text"));
            properties.put("keywords", Map.of("type", "text"));
            properties.put("content", Map.of("type", "text"));
            properties.put("verified_at", Map.of("type", "date"));
            Map<String, Object> mapping = Map.of("mappings", Map.of("properties", properties));
            client().put().uri("/" + INDEX)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(mapping))
                    .retrieve().toBodilessEntity();
            log.info("[知识索引] 已创建索引 {}", INDEX);
        }
    }

    public List<Map<String, Object>> search(String query, String layer, int limit) throws Exception {
        int size = Math.min(Math.max(limit, 1), 10);
        List<Map<String, Object>> filters = new ArrayList<>();
        if (layer != null && !layer.isBlank()) {
            filters.add(Map.of("term", Map.of("layer", layer)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("_source", List.of("id", "layer", "path", "category", "priority", "question", "answer"));
        body.put("query", Map.of("bool", Map.of(
                "must", List.of(Map.of("multi_match", Map.of(
                        "query", query == null ? "" : query,
                        "fields", List.of("question^3", "title^2", "keywords^2", "answer", "content")))),
                "filter", filters)));
        String response = client().post()
                .uri("/" + INDEX + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve().body(String.class);
        return extractHits(response);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractHits(String response) throws Exception {
        Map<String, Object> root = objectMapper.readValue(response, Map.class);
        Map<String, Object> hits = (Map<String, Object>) root.get("hits");
        List<Map<String, Object>> list = (List<Map<String, Object>>) hits.get("hits");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> hit : list) {
            Map<String, Object> source = (Map<String, Object>) hit.get("_source");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", hit.get("_score"));
            item.put("id", source.get("id"));
            item.put("layer", source.get("layer"));
            item.put("path", source.get("path"));
            item.put("question", source.get("question"));
            item.put("answer", truncate(String.valueOf(source.get("answer")), 500));
            result.add(item);
        }
        return result;
    }

    public long count() {
        try {
            String response = client().get().uri("/" + INDEX + "/_count").retrieve().body(String.class);
            Map<?, ?> root = objectMapper.readValue(response, Map.class);
            return ((Number) root.get("count")).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private Map<String, Object> toDoc(KnowledgeCard card) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", card.id());
        doc.put("layer", card.layer());
        doc.put("path", card.path());
        doc.put("category", card.category());
        doc.put("priority", card.priority());
        doc.put("question", card.question());
        doc.put("answer", card.answer());
        doc.put("keywords", String.join(" ", card.keywords()));
        doc.put("content", card.content());
        doc.put("source_path", "my-xhs-ai/knowledge/" + card.path());
        doc.put("verified_at", Instant.now().toString());
        return doc;
    }

    private String encodeId(String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
