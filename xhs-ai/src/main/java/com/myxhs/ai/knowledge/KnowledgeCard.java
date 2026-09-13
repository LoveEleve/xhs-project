package com.myxhs.ai.knowledge;

import java.util.List;

/**
 * 知识卡片（三层：architecture / business / code-map / failure）
 */
public record KnowledgeCard(String id, String layer, String path, String category, String priority,
                            String question, String answer, List<String> keywords, String content) {
}
