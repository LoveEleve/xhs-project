package com.myxhs.ai.app.service.rag;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RAG 回答闭环（D3）：检索（hybrid）→ 带上下文模型回答 + 引用来源。
 * Gate：检索不到可信内容时拒答（不编造）。
 */
@Component
public class RagAnswerService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerService.class);

    private static final String SYSTEM = """
            你是 my-xhs 指标口径助手。只能依据下面提供的知识库内容回答。
            规则：
            1. 每个结论必须引用来源（用 [来源: <source>] 标注）；
            2. 知识库没有的内容，明确说"知识库中未检索到相关内容"，不得编造；
            3. 数字/口径以知识库为准，不猜测。
            """;

    private final RagKnowledgeService rag;
    private final ChatModel chatModel;

    public RagAnswerService(RagKnowledgeService rag, ChatModel chatModel) {
        this.rag = rag;
        this.chatModel = chatModel;
    }

    public Map<String, Object> answer(String query) throws Exception {
        List<Map<String, Object>> hits = rag.searchHybrid(query, 3);
        if (hits.isEmpty()) {
            return Map.of("status", "ok", "answer", "知识库中未检索到相关内容，无法回答。",
                    "hits", List.of());
        }
        // 构建上下文（含来源）
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> h = hits.get(i);
            ctx.append("【资料").append(i + 1).append("】\n")
                    .append("标题：").append(h.get("title")).append('\n')
                    .append("内容：").append(h.get("content")).append('\n')
                    .append("来源：").append(h.get("source")).append('\n');
        }
        String prompt = SYSTEM + "\n\n知识库内容：\n" + ctx + "\n\n用户问题：" + query;
        String answer = chatModel.chat(prompt);
        log.info("[rag-answer] query={} hits={} answerLen={}", query, hits.size(), answer == null ? 0 : answer.length());
        return Map.of(
                "status", "ok",
                "answer", answer == null ? "" : answer,
                "hits", hits);
    }
}
