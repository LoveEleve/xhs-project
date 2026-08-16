package com.myxhs.ai.app.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myxhs.ai.app.service.embedding.EmbeddingClient;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库（D3 第一切片）：指标字典 → ES BM25 索引 → 检索带引用回原文。
 * ⚠️ 基线为 BM25 关键词检索（无需 embedding）；dense/混合检索用共享 EmbeddingClient。
 * ES 调用用 HttpClient + Jackson（零新依赖，规避 ES 版本差异：BOM 8.12 vs 服务端 8.19）。
 */
@Component
public class RagKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeService.class);

    public static final String INDEX = "myxhs-ai-knowledge";

    private final ObjectMapper om;
    private final HttpClient http;
    private final String esUrl;
    private final String authHeader;
    private final EmbeddingClient embeddingClient;

    public RagKnowledgeService(ObjectMapper om,
                               @Value("${myxhs.ai.rag.es-url:http://21.130.247.89:19200}") String esUrl,
                               @Value("${myxhs.ai.rag.es-user:elastic}") String esUser,
                               @Value("${myxhs.ai.rag.es-pass:}") String esPass,
                               EmbeddingClient embeddingClient) {
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.esUrl = esUrl;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((esUser + ":" + esPass).getBytes());
        this.embeddingClient = embeddingClient;
    }

    /** 建索引（BM25 + dense_vector 2048 维；单节点 ES 规范 1 shard/0 副本——中间件约定 2026-08-16） */
    public void createIndex() throws Exception {
        ObjectNode body = om.createObjectNode();
        ObjectNode settings = body.putObject("settings");
        settings.putObject("index").put("number_of_shards", 1).put("number_of_replicas", 0);
        ObjectNode mapping = body.putObject("mappings");
        ObjectNode props = mapping.putObject("properties");
        props.putObject("title").put("type", "text");
        props.putObject("content").put("type", "text");
        props.putObject("source").put("type", "keyword");
        props.putObject("section").put("type", "keyword");
        ObjectNode vec = props.putObject("embedding");
        vec.put("type", "dense_vector");
        vec.put("dims", 2048);
        vec.put("index", true);
        vec.put("similarity", "cosine");
        String resp = es("PUT", "/" + INDEX, om.writeValueAsString(body));
        log.info("[rag] 索引就绪: {}", INDEX);
    }

    /** 删除索引（重建用） */
    public void deleteIndex() throws Exception {
        es("DELETE", "/" + INDEX, null);
    }

    /**
     * 入库：按行分块（口径定义一行一块），批量索引。
     * @param docs [{title, content, source, section}]
     */
    public void ingest(List<Map<String, String>> docs) throws Exception {
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, String> d = docs.get(i);
            ObjectNode action = om.createObjectNode();
            action.putObject("index").put("_index", INDEX).put("_id", "doc-" + i);
            bulk.append(om.writeValueAsString(action)).append('\n');
            ObjectNode src = om.createObjectNode();
            src.put("title", d.get("title"));
            src.put("content", d.get("content"));
            src.put("source", d.get("source"));
            src.put("section", d.get("section"));
            // dense：文档向量（Agent Plan embedding），数组形式（非字符串）
            if (embeddingAvailable()) {
                src.set("embedding", om.valueToTree(embeddingClient.embed(d.get("content") + " " + d.get("title"))));
            }
            bulk.append(om.writeValueAsString(src)).append('\n');
        }
        String resp = es("POST", "/_bulk", bulk.toString());
        // bulk HTTP 200 也可能逐条失败 → 校验 items 错误
        JsonNode bulkResp = om.readTree(resp);
        int errors = 0;
        for (JsonNode item : bulkResp.path("items")) {
            JsonNode idx = item.path("index");
            if (idx.path("error").isMissingNode() == false) {
                errors++;
                log.warn("[rag] bulk 单条失败: id={}, err={}", idx.path("_id").asText(), idx.path("error"));
            }
        }
        if (errors > 0) {
            throw new IllegalStateException("入库失败 " + errors + " 条");
        }
        log.info("[rag] 入库完成: {} 条 (dense={})", docs.size(), embeddingAvailable());
    }

    /** dense 检索（kNN cosine）；无 embedding 配置时回退 BM25 */
    public List<Map<String, Object>> searchDense(String query, int size) throws Exception {
        if (!embeddingClient.available()) {
            log.warn("[rag] 未配置 embedding，dense 检索不可用");
            return List.of();
        }
        float[] q = embeddingClient.embed(query);
        ObjectNode body = om.createObjectNode();
        body.put("size", size);
        ObjectNode knn = body.putObject("knn");
        knn.put("field", "embedding");
        ArrayNode queryVector = om.createArrayNode();
        for (float f : q) {
            queryVector.add(f);
        }
        knn.set("query_vector", queryVector);
        knn.put("k", size);
        knn.put("num_candidates", size * 10);
        String resp = es("POST", "/" + INDEX + "/_search", om.writeValueAsString(body));
        return parseHits(om.readTree(resp));
    }

    private boolean embeddingAvailable() {
        return embeddingClient.available();
    }

    /** BM25 检索：返回 [{title, content, source, score}] */
    public List<Map<String, Object>> search(String query, int size) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("size", size);
        ObjectNode queryNode = body.putObject("query").putObject("multi_match");
        queryNode.put("query", query);
        ArrayNode fields = queryNode.putArray("fields");
        fields.add("content^2");
        fields.add("title^3");

        String resp = es("POST", "/" + INDEX + "/_search", om.writeValueAsString(body));
        return parseHits(om.readTree(resp));
    }

    private List<Map<String, Object>> parseHits(JsonNode root) {
        JsonNode hits = root.path("hits").path("hits");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode h : hits) {
            JsonNode src = h.path("_source");
            out.add(Map.of(
                    "id", h.path("_id").asText(),
                    "title", src.path("title").asText(),
                    "content", src.path("content").asText(),
                    "source", src.path("source").asText(),
                    "section", src.path("section").asText(),
                    "score", String.format("%.4f", h.path("_score").asDouble())));
        }
        return out;
    }

    /**
     * 混合检索：BM25 + dense 双路，RRF 融合（k=60）。
     * 返回 top N，score = RRF 分（保留单路分数在 content 备注）。
     */
    public List<Map<String, Object>> searchHybrid(String query, int size) throws Exception {
        List<Map<String, Object>> bm25 = search(query, size * 3);
        List<Map<String, Object>> dense = List.of();
        if (embeddingAvailable()) {
            try {
                dense = searchDense(query, size * 3);
            } catch (Exception e) {
                // dense 失败（embedding 服务异常）→ 降级 BM25，不阻断检索
                log.warn("[rag] dense 检索失败，降级 BM25: {}", e.getMessage());
            }
        }
        return rrfFuse(bm25, dense, size);
    }

    /** RRF 融合（k=60，并列按 docId 决胜）——纯逻辑可单测 */
    static List<Map<String, Object>> rrfFuse(List<Map<String, Object>> bm25, List<Map<String, Object>> dense, int size) {
        java.util.Map<String, Double> rrf = new java.util.HashMap<>();
        java.util.Map<String, Map<String, Object>> docs = new java.util.HashMap<>();
        addRrf(bm25, rrf, docs);
        addRrf(dense, rrf, docs);

        List<Map<String, Object>> out = new ArrayList<>();
        rrf.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue()); // rrf 降序
                    return c != 0 ? c : a.getKey().compareTo(b.getKey()); // 并列按 id 决胜（确定性）
                })
                .limit(size)
                .forEach(e -> {
                    Map<String, Object> d = new java.util.HashMap<>(docs.get(e.getKey()));
                    d.put("rrf", String.format("%.4f", e.getValue()));
                    out.add(d);
                });
        return out;
    }

    private static void addRrf(List<Map<String, Object>> hits, java.util.Map<String, Double> rrf,
                               java.util.Map<String, Map<String, Object>> docs) {
        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> h = hits.get(i);
            String id = (String) h.get("id");
            rrf.merge(id, 1.0 / (60 + i + 1), Double::sum);
            docs.putIfAbsent(id, h);
        }
    }

    private String es(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(esUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            b.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        } else if ("PUT".equals(method)) {
            b.PUT(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.DELETE();
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException("ES " + method + " " + path + " 失败: " + r.statusCode() + " " + r.body());
        }
        return r.body();
    }
}
