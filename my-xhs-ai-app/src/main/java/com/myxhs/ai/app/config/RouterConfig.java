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
 * 路由装配（分层，主次正确）：L0 确定性规则（极简）→ L1 LLM 意图分类（默认开启，主路径）
 * → L2 语义层降级（embedding）→ L3 默认引导。
 * LLM 分类成本极低（flash 单次 ~500 token），远低于误路由进 Agent 白跑调查的成本；
 * 可配置关闭（纯规则模式，测试/离线）。
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
                                     @Value("${myxhs.ai.router.llm-fallback.enabled:true}") boolean llmFallbackEnabled) {
        return new IntentRouter(semanticClassifier, llmFallbackEnabled ? llmClassifier : null);
    }

    @Bean
    @ConditionalOnProperty(name = "myxhs.ai.router.llm-fallback.enabled", havingValue = "true")
    public LlmIntentClassifier llmIntentClassifier(ChatModel chatModel) {
        return new LlmIntentClassifierImpl(chatModel);
    }
}
