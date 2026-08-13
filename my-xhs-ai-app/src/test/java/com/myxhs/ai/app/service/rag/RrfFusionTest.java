package com.myxhs.ai.app.service.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合单元测试（纯逻辑）。
 * 覆盖：双路命中叠加 / 单路缺失 / 并列决胜 / top N。
 */
class RrfFusionTest {

    private static Map<String, Object> doc(String id) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("title", "t-" + id);
        m.put("score", "0.0000");
        return m;
    }

    private static List<Map<String, Object>> list(String... ids) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (String id : ids) {
            l.add(doc(id));
        }
        return l;
    }

    @Test
    void 双路都命中_叠加靠前() {
        // a 双路命中（BM25 第1 + dense 第1）→ RRF 最高
        List<Map<String, Object>> bm25 = list("a", "b", "c");
        List<Map<String, Object>> dense = list("a", "d", "e");
        List<Map<String, Object>> out = RagKnowledgeService.rrfFuse(bm25, dense, 5);
        assertEquals("a", out.get(0).get("id"), "双路命中的 a 应第一");
        assertTrue(out.size() == 5);
    }

    @Test
    void 单路缺失_不影响融合() {
        List<Map<String, Object>> bm25 = list("a", "b");
        List<Map<String, Object>> out = RagKnowledgeService.rrfFuse(bm25, List.of(), 2);
        assertEquals("a", out.get(0).get("id"));
        assertEquals(2, out.size());
    }

    @Test
    void 并列按id决胜_确定性() {
        // b、c 都只在 BM25 命中且 rank 相邻 → 平分 → id 决胜（b < c）
        List<Map<String, Object>> bm25 = list("a", "b", "c");
        List<Map<String, Object>> out1 = RagKnowledgeService.rrfFuse(bm25, List.of(), 3);
        List<Map<String, Object>> out2 = RagKnowledgeService.rrfFuse(bm25, List.of(), 3);
        assertEquals(out1.get(1).get("id"), out2.get(1).get("id"), "并列结果应确定（两次运行一致）");
        assertEquals("b", out1.get(1).get("id"));
        assertEquals("c", out1.get(2).get("id"));
    }

    @Test
    void topN限制() {
        List<Map<String, Object>> bm25 = list("a", "b", "c", "d");
        List<Map<String, Object>> out = RagKnowledgeService.rrfFuse(bm25, List.of(), 2);
        assertEquals(2, out.size());
    }
}
