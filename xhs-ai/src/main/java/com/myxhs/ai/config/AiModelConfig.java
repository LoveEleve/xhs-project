package com.myxhs.ai.config;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型配置（M1-2 最小闭环：siyu-all OpenAI 兼容网关）
 * <p>模型网关（路由/熔断/降级/预算）在 M2 接入，见 docs/design/01-model-gateway.md</p>
 */
@Configuration
public class AiModelConfig {

    @Bean
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
}
