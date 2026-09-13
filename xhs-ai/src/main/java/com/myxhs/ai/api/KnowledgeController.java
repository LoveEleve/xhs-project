package com.myxhs.ai.api;

import com.myxhs.ai.common.R;
import com.myxhs.ai.knowledge.KnowledgeIndexer;
import com.myxhs.ai.knowledge.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库管理（统计/重建索引）
 */
@RestController
@RequestMapping("/api/ai/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeIndexer knowledgeIndexer;

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cards", knowledgeRepository.all().size());
        stats.put("indexed", knowledgeIndexer.count());
        return R.ok(stats);
    }

    @PostMapping("/reindex")
    public R<Map<String, Object>> reindex() {
        return R.ok(knowledgeIndexer.reindex());
    }
}
