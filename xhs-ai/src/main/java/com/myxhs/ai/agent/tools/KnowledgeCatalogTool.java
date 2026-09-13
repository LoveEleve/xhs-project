package com.myxhs.ai.agent.tools;

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

/**
 * 知识目录（read-only；渐进披露入口：先看目录，再检索/读卡）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeCatalogTool implements AgentTool {

    private final KnowledgeRepository knowledgeRepository;

    @Override
    public String getName() {
        return "knowledge_catalog";
    }

    @Override
    public String getDescription() {
        return "列出知识库目录（按层：architecture/business/code-map/failure 的卡片 id 与标题）。"
                + "回答系统本体问题（架构/业务链路/代码结构）前先调用本工具建立全局视图。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("layer", Map.of("type", "string", "description", "可选：只列某一层"));
        return ToolSupport.schema(props, List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            String layer = ToolSupport.arg(param, "layer");
            return ToolSupport.result(param, ToolSupport.json(knowledgeRepository.catalog(layer)));
        } catch (Exception e) {
            log.warn("[知识] catalog 失败: {}", e.getMessage());
            return ToolSupport.error(param, "知识目录获取失败（内部错误）");
        }
    }
}
