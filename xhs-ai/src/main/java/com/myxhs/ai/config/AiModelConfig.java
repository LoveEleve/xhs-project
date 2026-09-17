package com.myxhs.ai.config;

import com.myxhs.ai.model.ModelGateway;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 模型配置（M1-2 最小闭环：siyu-all OpenAI 兼容网关）
 * <p>模型网关（路由/熔断/降级/预算）在 M2 接入，见 docs/design/01-model-gateway.md</p>
 */
@Configuration
public class AiModelConfig {

    /**
     * 模型 HTTP 传输层（显式超时）。
     * <p>RV-fix：此前 builder 未配置 transport，走 5 分钟默认读/响应超时——模型侧挂起时
     * 请求会一直等到 MVC async 300s 兜底才 500。这里显式设置连接/读/写/流空闲/响应超时，
     * 让"卡住"在 90s 内变为可重试的传输错误（ModelGateway 重试/降级），而不是干挂 5 分钟。</p>
     */
    @Bean("modelHttpTransport")
    public HttpTransport modelHttpTransport(
            @Value("${ai.model.http.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${ai.model.http.read-timeout-ms:90000}") long readTimeoutMs,
            @Value("${ai.model.http.write-timeout-ms:30000}") long writeTimeoutMs,
            @Value("${ai.model.http.stream-idle-timeout-ms:90000}") long streamIdleTimeoutMs,
            @Value("${ai.model.http.response-timeout-ms:240000}") long responseTimeoutMs) {
        return JdkHttpTransport.builder()
                .config(HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .readTimeout(Duration.ofMillis(readTimeoutMs))
                        .writeTimeout(Duration.ofMillis(writeTimeoutMs))
                        .streamIdleTimeout(Duration.ofMillis(streamIdleTimeoutMs))
                        .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                        .build())
                .build();
    }

    @Bean
    public OpenAIChatModel openAIChatModel(
            @Value("${ai.model.base-url}") String baseUrl,
            @Value("${ai.model.api-key}") String apiKey,
            @Value("${ai.model.name}") String modelName,
            @Qualifier("modelHttpTransport") HttpTransport httpTransport) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .httpTransport(httpTransport)
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
            @Value("${ai.model.agent-name:qwen3.8-flash}") String modelName,
            @Qualifier("modelHttpTransport") HttpTransport httpTransport) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .httpTransport(httpTransport)
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
            @Value("${ai.model.light-name:deepseek-v4-flash}") String modelName,
            @Qualifier("modelHttpTransport") HttpTransport httpTransport) {
        return OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(true)
                .httpTransport(httpTransport)
                .build();
    }
}
