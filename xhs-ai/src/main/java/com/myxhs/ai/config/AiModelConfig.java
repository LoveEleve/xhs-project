package com.myxhs.ai.config;

import com.myxhs.ai.model.ModelGateway;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 模型网关（D01 最小落地）：主/备 + 传输重试 + 熔断 + 指标 */
    @Bean
    @Primary
    public Model modelGateway(@Qualifier("openAIChatModel") OpenAIChatModel primary,
                              @Qualifier("lightChatModel") OpenAIChatModel lightChatModel,
                              com.myxhs.ai.model.TokenBudgetService tokenBudgetService,
                              MeterRegistry meterRegistry,
                              @Value("${ai.model.retry.max-attempts:2}") int maxAttempts,
                              @Value("${ai.model.retry.backoff-ms:500}") long backoffMs,
                              @Value("${ai.model.breaker.threshold:3}") int breakerThreshold,
                              @Value("${ai.model.breaker.cooldown-ms:60000}") long breakerCooldownMs) {
        return new ModelGateway(primary, lightChatModel, tokenBudgetService, meterRegistry,
                maxAttempts, backoffMs, breakerThreshold, breakerCooldownMs);
    }

    /** Agent 专用模型（工具循环：低推理、稳定 tool_calls；M3/D01 决策） */
    @Bean("agentChatModel")
    public OpenAIChatModel agentChatModel(
            @Value("${ai.model.base-url}") String baseUrl,
            @Value("${ai.model.api-key}") String apiKey,
            @Value("${ai.model.agent-name:qwen3.8-flash}") String modelName) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /** Agent 模型网关（工具循环专用；重试/熔断/降级与聊天网关同策略） */
    @Bean("agentModel")
    public Model agentModel(@Qualifier("agentChatModel") OpenAIChatModel agentChatModel,
                            @Qualifier("lightChatModel") OpenAIChatModel lightChatModel,
                            com.myxhs.ai.model.TokenBudgetService tokenBudgetService,
                            MeterRegistry meterRegistry,
                            @Value("${ai.model.retry.max-attempts:2}") int maxAttempts,
                            @Value("${ai.model.retry.backoff-ms:500}") long backoffMs,
                            @Value("${ai.model.breaker.threshold:3}") int breakerThreshold,
                            @Value("${ai.model.breaker.cooldown-ms:60000}") long breakerCooldownMs) {
        return new ModelGateway(agentChatModel, lightChatModel, tokenBudgetService, meterRegistry,
                maxAttempts, backoffMs, breakerThreshold, breakerCooldownMs);
    }

    /** 降级/轻量模型（由模型网关调用） */
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
