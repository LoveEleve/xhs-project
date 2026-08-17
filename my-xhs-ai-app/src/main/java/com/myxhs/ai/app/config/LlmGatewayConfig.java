package com.myxhs.ai.app.config;

import java.time.Duration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * LLM 网关模型配置（手工集成 LangChain4j，无官方 Boot starter）。
 * OpenAI 兼容格式；密钥只从环境变量/配置读取，绝不落库。
 * 2026-08-14：Provider 切 OpenCode Go（opencode.ai/zen/go/v1）；
 * 2026-08-17：主模型切到 `mimo-v2.5-pro`（go 通道模型目录实测存在，付费档，可稳定通过 eval-gate 关键门禁）。
 */
@Configuration
public class LlmGatewayConfig {

    public static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/go/v1";

    @Bean
    public ChatModel chatModel(
            @Value("${myxhs.ai.llm.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${myxhs.ai.llm.model:mimo-v2.5-pro}") String model,
            @Value("${myxhs.ai.llm.api-key:}") String apiKey,
            @Value("${myxhs.ai.llm.timeout-seconds:60}") long timeoutSeconds) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolveKey(apiKey))
                .modelName(resolveModel(model))
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(
            @Value("${myxhs.ai.llm.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${myxhs.ai.llm.model:mimo-v2.5-pro}") String model,
            @Value("${myxhs.ai.llm.api-key:}") String apiKey,
            @Value("${myxhs.ai.llm.timeout-seconds:60}") long timeoutSeconds) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolveKey(apiKey))
                .modelName(resolveModel(model))
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private static String resolveKey(String configured) {
        String key = StringUtils.hasText(configured) ? configured : System.getenv("MYXHS_LLM_API_KEY");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException(
                    "MYXHS_LLM_API_KEY 未配置（OpenCode Go API Key，见 opencode.ai/auth；"
                            + "或 myxhs.ai.llm.api-key），密钥不得落库。");
        }
        return key;
    }

    private static String resolveModel(String configured) {
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("myxhs.ai.llm.model 未配置");
        }
        return configured;
    }
}
