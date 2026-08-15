package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.embedding.EmbeddingClient;
import com.myxhs.ai.app.service.router.IntentRouter;
import com.myxhs.ai.app.service.router.LlmIntentClassifier;
import com.myxhs.ai.app.service.router.LlmIntentClassifierImpl;
import com.myxhs.ai.app.service.router.SemanticIntentClassifier;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由装配（三阶）：L0 规则（确定性）→ L1 语义（embedding few-shot，自动启用）→ L2 LLM 兜底（默认关）。
 * 语义层复用 RAG embedding 基础设施（myxhs.ai.rag.embedding-*），未配置时自动降级到规则+默认引导。
 */
@Configuration
public class RouterConfig {

    @Bean
    public SemanticIntentClassifier semanticIntentClassifier(EmbeddingClient embeddingClient,
                                                             @Value("${myxhs.ai.router.semantic-threshold:0.42}") double threshold) {
        return new SemanticIntentClassifier(embeddingClient, threshold);
    }

    @Bean
    public IntentRouter intentRouter(SemanticIntentClassifier semanticClassifier,
                                     @Autowired(required = false) LlmIntentClassifier llmClassifier,
                                     @Value("${myxhs.ai.router.llm-fallback.enabled:false}") boolean llmFallbackEnabled) {
        return new IntentRouter(semanticClassifier, llmFallbackEnabled ? llmClassifier : null);
    }

    @Bean
    @ConditionalOnProperty(name = "myxhs.ai.router.llm-fallback.enabled", havingValue = "true")
    public LlmIntentClassifier llmIntentClassifier(ChatModel chatModel) {
        return new LlmIntentClassifierImpl(chatModel);
    }
}
