package com.myxhs.ai.app.service.knowledge;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 第一版系统知识路由：
 * - SYSTEM_KNOWLEDGE -> architecture + business cards
 * - CODE_STRUCTURE -> architecture/code maps（当前先复用 architecture + code-map）
 */
@Service
public class KnowledgeRoutingService {

    private final KnowledgeCardLoader loader;
    private final KnowledgeQuestionClassifier classifier;
    private final KnowledgeAnswerComposer composer;

    public KnowledgeRoutingService(KnowledgeCardLoader loader,
                                   KnowledgeQuestionClassifier classifier,
                                   KnowledgeAnswerComposer composer) {
        this.loader = loader;
        this.classifier = classifier;
        this.composer = composer;
    }

    public boolean matches(String question) {
        return classifier.classify(question) != null;
    }

    public String answer(String question) {
        KnowledgeQuestionType type = classifier.classify(question);
        if (type == null) {
            return null;
        }
        List<KnowledgeCard> cards = new ArrayList<>();
        if (type == KnowledgeQuestionType.SYSTEM_KNOWLEDGE) {
            cards.addAll(filter(loader.loadCards("architecture"), question));
            cards.addAll(filter(loader.loadCards("business"), question));
        } else {
            cards.addAll(filter(loader.loadCards("architecture"), question));
            cards.addAll(filter(loader.loadCards("code-map"), question));
        }
        return composer.compose(question, cards);
    }

    private List<KnowledgeCard> filter(List<KnowledgeCard> cards, String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<ScoredCard> scored = new ArrayList<>();
        for (KnowledgeCard c : cards) {
            int score = 0;
            for (String keyword : c.triggerKeywords()) {
                if (!keyword.isBlank() && q.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score += keyword.length() >= 4 ? 5 : 2;
                }
            }
            if (c.bestForQuestions() != null) {
                for (String candidate : c.bestForQuestions()) {
                    String normalized = candidate.toLowerCase(Locale.ROOT).replace("？", "").replace("?", "");
                    if (!normalized.isBlank() && q.contains(normalized)) score += 8;
                }
            }
            if (score > 0) scored.add(new ScoredCard(c, score));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        return scored.stream().map(ScoredCard::card).limit(5).toList();
    }

    private record ScoredCard(KnowledgeCard card, int score) {
    }
}
