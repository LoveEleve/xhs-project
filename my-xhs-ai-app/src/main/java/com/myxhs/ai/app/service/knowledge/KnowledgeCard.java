package com.myxhs.ai.app.service.knowledge;

import java.util.List;
import java.util.Map;

/** 第一版系统知识卡（从 YAML 读入）。 */
public record KnowledgeCard(
        String id,
        String category,
        String priority,
        String question,
        String answerShape,
        String serveMode,
        String scope,
        String answer,
        Map<String, String> structuredPoints,
        String whyItMatters,
        List<String> antiConfusion,
        List<String> bestForQuestions,
        List<String> triggerKeywords,
        List<Map<String, String>> sources,
        List<String> evidenceLevel,
        String confidence,
        List<String> followupDocs,
        List<String> relatedCards
) {
}
