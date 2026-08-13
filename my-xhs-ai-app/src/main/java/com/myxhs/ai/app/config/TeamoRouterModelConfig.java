package com.myxhs.ai.app.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * TeamoRouter 模型配置（手工集成 LangChain4j，无官方 Boot starter）。
 * OpenAI 兼容格式；密钥只从环境变量/配置读取，绝不落库。
 */
@Configuration
public class TeamoRouterModelConfig {

    public static final String DEFAULT_BASE_URL = "https://api.teamorouter.com/v1";

    @Bean
    public ChatModel chatModel(
            @Value("${myxhs.ai.teamo.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${myxhs.ai.teamo.model:}") String model,
            @Value("${myxhs.ai.teamo.api-key:}") String apiKey) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolveKey(apiKey))
                .modelName(resolveModel(model))
                .temperature(0.0)
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(
            @Value("${myxhs.ai.teamo.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${myxhs.ai.teamo.model:}") String model,
            @Value("${myxhs.ai.teamo.api-key:}") String apiKey) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolveKey(apiKey))
                .modelName(resolveModel(model))
                .temperature(0.0)
                .build();
    }

    private static String resolveKey(String configured) {
        String key = StringUtils.hasText(configured) ? configured : System.getenv("TEAMO_API_KEY");
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException(
                    "TEAMO_API_KEY 未配置（环境变量或 myxhs.ai.teamo.api-key），密钥不得落库。");
        }
        return key;
    }

    private static String resolveModel(String configured) {
        if (!StringUtils.hasText(configured)) {
            throw new IllegalStateException("myxhs.ai.teamo.model 未配置");
        }
        return configured;
    }
}
