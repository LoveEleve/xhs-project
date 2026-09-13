package com.myxhs.ai.api;

import com.myxhs.ai.common.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import com.myxhs.ai.code.CodeLocateService;
import com.myxhs.ai.eval.KbEvalService;
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
    private final KbEvalService kbEvalService;
    private final CodeLocateService codeLocateService;

    @Value("${myxhs.admin.token:}")
    private String adminToken;

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cards", knowledgeRepository.all().size());
        stats.put("indexed", knowledgeIndexer.count());
        return R.ok(stats);
    }

    @PostMapping("/reindex")
    public ResponseEntity<R<Map<String, Object>>> reindex(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!privileged(adminCall, internalCall)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理令牌"));
        }
        return ResponseEntity.ok(R.ok(knowledgeIndexer.reindex()));
    }

    private boolean privileged(String adminCall, String internalCall) {
        return (adminToken != null && !adminToken.isEmpty() && adminToken.equals(adminCall))
                || (internalToken != null && !internalToken.isEmpty() && internalToken.equals(internalCall));
    }

    /** 代码定位（只读；冒烟/评测入口） */
    @org.springframework.web.bind.annotation.GetMapping("/code-locate")
    public R<Map<String, Object>> locate(@org.springframework.web.bind.annotation.RequestParam("q") String query,
                                         @org.springframework.web.bind.annotation.RequestParam(value = "limit", required = false) Integer limit) {
        return R.ok(codeLocateService.locate(query, limit));
    }

    /** KB 检索评测（hit@1/hit@3 门禁；M3 出口） */
    @PostMapping("/eval")
    public ResponseEntity<R<Map<String, Object>>> eval(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!privileged(adminCall, internalCall)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "需要管理令牌"));
        }
        return ResponseEntity.ok(R.ok(kbEvalService.run()));
    }
}
