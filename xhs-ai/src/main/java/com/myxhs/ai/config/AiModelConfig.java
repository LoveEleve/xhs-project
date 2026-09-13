package com.myxhs.ai.config;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型配置（M1-2 最小闭环：siyu-all OpenAI 兼容网关）
 * <p>模型网关（路由/熔断/降级/预算）在 M2 接入，见 docs/design/01-model-gateway.md</p>
 */
@Configuration
public class AiModelConfig {

    @Bean
    @Primary
    public OpenAIChatModel openAIChatModel(
            @Value("${ai.model.base-url}") String baseUrl,
            @Value("${ai.model.api-key}") String apiKey,
            @Value("${ai.model.name}") String modelName) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /** 降级/轻量模型（主模型传输失败时由 Agent fallback 使用；M3 模型网关最小落点） */
    @Bean("lightChatModel")
    public OpenAIChatModel lightChatModel(
            @Value("${ai.model.base-url}") String baseUrl,
            @Value("${ai.model.api-key}") String apiKey,
            @Value("${ai.model.light-name:deepseek-v4-flash}") String modelName) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .build();
    }
}
