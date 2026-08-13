package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.router.IntentRouter;
import com.myxhs.ai.app.service.router.LlmIntentClassifier;
import com.myxhs.ai.app.service.router.LlmIntentClassifierImpl;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由装配：IntentRouter（规则优先 + 可选 LLM 兜底）。
 * LLM 兜底默认关闭（myxhs.ai.router.llm-fallback.enabled=false）——规则路径保持确定性、无模型依赖；
 * 开启后仅规则模糊时走 LLM 分类（D4 方向）。
 */
@Configuration
public class RouterConfig {

    @Bean
    public IntentRouter intentRouter(
            @Autowired(required = false) LlmIntentClassifier llmClassifier,
            @Value("${myxhs.ai.router.llm-fallback.enabled:false}") boolean llmFallbackEnabled) {
        return new IntentRouter(llmFallbackEnabled ? llmClassifier : null);
    }

    @Bean
    @ConditionalOnProperty(name = "myxhs.ai.router.llm-fallback.enabled", havingValue = "true")
    public LlmIntentClassifier llmIntentClassifier(ChatModel chatModel) {
        return new LlmIntentClassifierImpl(chatModel);
    }
}
