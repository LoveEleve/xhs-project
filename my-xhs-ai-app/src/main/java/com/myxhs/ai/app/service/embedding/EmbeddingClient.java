package com.myxhs.ai.app.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 共享 Embedding 客户端（M8-4 语义路由）：RAG dense 检索与意图语义分类共用同一基础设施
 * （火山 Agent Plan doubao-embedding-vision-large，2048 维）。
 * 配置键与 RAG 一致（myxhs.ai.rag.embedding-*）；embedding 未配置时 available()=false，调用方降级。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final ObjectMapper om;
    private final HttpClient http;
    private final String embeddingUrl;
    private final String embeddingKey;
    private final String embeddingModel;

    public EmbeddingClient(ObjectMapper om,
                           @Value("${myxhs.ai.rag.embedding-url:}") String embeddingUrl,
                           @Value("${myxhs.ai.rag.embedding-key:}") String embeddingKey,
                           @Value("${myxhs.ai.rag.embedding-model:doubao-embedding-vision-large}") String embeddingModel) {
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.embeddingUrl = embeddingUrl;
        this.embeddingKey = embeddingKey;
        this.embeddingModel = embeddingModel;
    }

    public boolean available() {
        return embeddingUrl != null && !embeddingUrl.isBlank()
                && embeddingKey != null && !embeddingKey.isBlank();
    }

    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /** 批量向量化（单次 HTTP 调用，种子库/批量入库用）；API 单批上限 10 条，超限自动分批 */
    public List<float[]> embedAll(List<String> texts) {
        if (!available()) {
            throw new IllegalStateException("embedding 未配置");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_MAX) {
            out.addAll(embedBatch(texts.subList(i, Math.min(texts.size(), i + BATCH_MAX))));
        }
        return out;
    }

    private static final int BATCH_MAX = 10;
    /** 429 限流退避重试（ARK 频率限制常见；最多 2 次，间隔递增） */
    private static final int MAX_RETRIES = 2;

    private List<float[]> embedBatch(List<String> texts) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("embedding 重试中断", ie);
                }
            }
            try {
                return embedOnce(texts);
            } catch (IllegalStateException e) {
                last = e;
                if (!isRateLimited(e.getMessage())) {
                    break; // 非限流错误不重试
                }
                log.warn("[embedding] 限流(429)重试 {}/{}", attempt + 1, MAX_RETRIES);
            }
        }
        throw last == null ? new IllegalStateException("embedding 调用失败") : last;
    }

    private static boolean isRateLimited(String msg) {
        return msg != null && msg.contains("429");
    }

    private List<float[]> embedOnce(List<String> texts) {
        try {
            ObjectNode body = om.createObjectNode();
            body.put("model", embeddingModel);
            com.fasterxml.jackson.databind.node.ArrayNode arr = body.putArray("input");
            for (String t : texts) {
                arr.add(t);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(embeddingUrl + "/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + embeddingKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("embedding 失败: " + r.statusCode() + " " + r.body());
            }
            JsonNode data = om.readTree(r.body()).path("data");
            if (data.size() == 0) {
                throw new IllegalStateException("embedding 空响应");
            }
            List<float[]> out = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode vec = item.path("embedding");
                float[] v = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    v[i] = (float) vec.get(i).asDouble();
                }
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("[embedding] 向量化失败: {}", e.getMessage());
            throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
        }
    }
}
