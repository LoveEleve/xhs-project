package com.myxhs.ai.app.controller;

import com.myxhs.ai.app.service.rag.MetricDictionaryIngester;
import com.myxhs.ai.app.service.rag.RagAnswerService;
import com.myxhs.ai.app.service.rag.RagKnowledgeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 端点（D3）：入库 + 检索（BM25 / dense / hybrid-RRF）+ 回答（带引用），错误统一返回 error JSON（不 500）。
 */
@RestController
@RequestMapping("/api/ai/rag")
public class RagController {

    private final RagKnowledgeService rag;
    private final MetricDictionaryIngester ingester;
    private final RagAnswerService ragAnswerService;

    public RagController(RagKnowledgeService rag, MetricDictionaryIngester ingester,
                         RagAnswerService ragAnswerService) {
        this.rag = rag;
        this.ingester = ingester;
        this.ragAnswerService = ragAnswerService;
    }

    /** RAG 回答：检索 → 模型 → 自然语言 + 引用；检索不到拒答 */
    @PostMapping("/answer")
    public Map<String, Object> answer(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        try {
            return ragAnswerService.answer(query);
        } catch (Exception e) {
            return Map.of("status", "error", "error", String.valueOf(e.getMessage()));
        }
    }

    /** 建索引 + 入库指标字典（幂等：先删后建） */
    @PostMapping("/ingest")
    public Map<String, Object> ingest() {
        try {
            try {
                rag.deleteIndex();
            } catch (Exception ignored) {
                // 索引不存在
            }
            rag.createIndex();
            java.util.List<Map<String, String>> docs = ingester.parseFromClasspath();
            rag.ingest(docs);
            return Map.of("status", "ok", "ingested", docs.size());
        } catch (Exception e) {
            return Map.of("status", "error", "error", String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> safe(String mode, java.util.function.Supplier<java.util.List<Map<String, Object>>> fn,
                                     String query) {
        try {
            return Map.of("query", query, "mode", mode, "hits", fn.get());
        } catch (Exception e) {
            return Map.of("query", query, "mode", mode, "status", "error", "error", String.valueOf(e.getMessage()));
        }
    }

    /** 检索（BM25）*/
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return safe("bm25", () -> {
            try {
                return rag.search(query, 3);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, query);
    }

    /** dense 检索（kNN embedding）*/
    @PostMapping("/search-dense")
    public Map<String, Object> searchDense(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return safe("dense", () -> {
            try {
                return rag.searchDense(query, 3);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, query);
    }

    /** 混合检索（BM25 + dense + RRF）*/
    @PostMapping("/search-hybrid")
    public Map<String, Object> searchHybrid(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return safe("hybrid-rrf", () -> {
            try {
                return rag.searchHybrid(query, 3);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, query);
    }
}
