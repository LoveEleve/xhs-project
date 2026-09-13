package com.myxhs.ai.agent.tools;

import com.myxhs.ai.knowledge.KnowledgeIndexer;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索（read-only；BM25 + layer 过滤，返回卡片引用，不做 chunk）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    private final KnowledgeIndexer knowledgeIndexer;

    @Override
    public String getName() {
        return "knowledge_search";
    }

    @Override
    public String getDescription() {
        return "按问题检索知识卡片（BM25+关键词，支持按层过滤）。返回命中卡片的 id/path/摘要；"
                + "拿到 id/path 后用 card_read 读整卡，回答必须引用卡片来源。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "检索问题/关键词"));
        props.put("layer", Map.of("type", "string", "description", "可选层：architecture/business/code-map/failure"));
        props.put("limit", Map.of("type", "integer", "description", "返回条数（默认 5，最大 10）"));
        return ToolSupport.schema(props, List.of("query"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = ToolSupport.arg(param, "query");
        if (query == null || query.isBlank()) {
            return ToolSupport.error(param, "缺少必填参数 query");
        }
        try {
            int limit = parseLimit(ToolSupport.arg(param, "limit"));
            List<Map<String, Object>> hits = knowledgeIndexer.search(query, ToolSupport.arg(param, "layer"), limit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("hits", hits);
            result.put("hint", "命中后请调用 card_read(id) 读整卡；回答引用卡片 id/path。");
            return ToolSupport.result(param, ToolSupport.json(result));
        } catch (Exception e) {
            log.warn("[知识] 检索失败 query={}: {}", query, e.getMessage());
            return ToolSupport.error(param, "知识检索失败（内部错误）");
        }
    }

    private int parseLimit(String value) {
        try {
            return value == null ? 5 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 5;
        }
    }
}
