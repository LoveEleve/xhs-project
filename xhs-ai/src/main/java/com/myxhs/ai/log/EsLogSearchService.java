package com.myxhs.ai.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 日志检索（M2.0：按 msgId 关联首次失败日志）
 */
@Slf4j
@Service
public class EsLogSearchService {

    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EsLogSearchService(@Value("${MYXHS_ES_URL:}") String url,
                              @Value("${MYXHS_ES_USER:}") String user,
                              @Value("${MYXHS_ES_PASSWORD:}") String password) {
        String auth = Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder()
                .baseUrl(url)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Basic " + auth)
                .build();
    }

    /** 按候选词（originMsgId→msgId→keys）查最早一条相关日志（首错） */
    public Map<String, Object> firstFailure(String... candidates) {
        List<String> terms = java.util.Arrays.stream(candidates)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return Map.of("found", false, "searched", List.of());
        }
        for (String term : terms) {
            try {
                Map<String, Object> body = Map.of(
                        "size", 1,
                        "sort", List.of(Map.of("@timestamp", "asc")),
                        "_source", List.of("@timestamp", "APP_NAME", "level", "message", "stack_trace"),
                        "query", Map.of("bool", Map.of(
                                "should", List.of(
                                        Map.of("match_phrase", Map.of("message", term)),
                                        Map.of("match_phrase", Map.of("MSG_ID", term)),
                                        Map.of("match_phrase", Map.of("keys", term)),
                                        Map.of("match_phrase", Map.of("key", term)),
                                        Map.of("match_phrase", Map.of("UNIQ_KEY", term))),
                                "minimum_should_match", 1)));
                String response = client.post()
                        .uri("/myxhs-logs-*/_search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(body))
                        .retrieve()
                        .body(String.class);
                Map<String, Object> hit = extractFirstHit(response);
                if (Boolean.TRUE.equals(hit.get("found"))) {
                    hit.put("matchedBy", term);
                    return hit;
                }
            } catch (Exception e) {
                log.warn("[ES] 首错日志检索失败 term={}, err={}", term, e.getMessage());
            }
        }
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("found", false);
        notFound.put("searched", terms);
        notFound.put("note", "日志中未命中 msgId/keys，可能日志未携带消息 ID（可从消息体与时间窗人工缩小范围）");
        return notFound;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFirstHit(String response) throws Exception {
        Map<String, Object> root = objectMapper.readValue(response, Map.class);
        Map<String, Object> hits = (Map<String, Object>) root.get("hits");
        List<Map<String, Object>> list = (List<Map<String, Object>>) hits.get("hits");
        if (list.isEmpty()) {
            return Map.of("found", false);
        }
        Map<String, Object> source = (Map<String, Object>) list.get(0).get("_source");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("timestamp", source.get("@timestamp"));
        result.put("app", source.get("APP_NAME"));
        result.put("level", source.get("level"));
        result.put("message", truncate(String.valueOf(source.get("message")), 600));
        return result;
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
