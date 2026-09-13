package com.myxhs.ai.agent.tools;

import com.myxhs.ai.knowledge.KnowledgeCard;
import com.myxhs.ai.knowledge.KnowledgeRepository;
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
import java.util.Optional;

/**
 * 读取整卡（read-only；small-to-big：小检索、大读取，避免 chunk 割裂）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardReadTool implements AgentTool {

    private static final int MAX_CONTENT = 4000;

    private final KnowledgeRepository knowledgeRepository;

    @Override
    public String getName() {
        return "card_read";
    }

    @Override
    public String getDescription() {
        return "按 id 或 path 读取知识卡片完整内容（含结构化要点/反例/来源）。回答系统本体问题必须基于整卡内容。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", Map.of("type", "string", "description", "卡片 id 或相对路径"));
        return ToolSupport.schema(props, List.of("id"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String id = ToolSupport.arg(param, "id");
        if (id == null || id.isBlank()) {
            return ToolSupport.error(param, "缺少必填参数 id");
        }
        Optional<KnowledgeCard> card = knowledgeRepository.byId(id);
        if (card.isEmpty()) {
            return ToolSupport.error(param, "卡片不存在: " + id);
        }
        KnowledgeCard c = card.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", c.id());
        result.put("layer", c.layer());
        result.put("path", c.path());
        result.put("category", c.category());
        result.put("priority", c.priority());
        result.put("question", c.question());
        result.put("content", truncate(c.content(), MAX_CONTENT));
        return ToolSupport.result(param, ToolSupport.json(result));
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
