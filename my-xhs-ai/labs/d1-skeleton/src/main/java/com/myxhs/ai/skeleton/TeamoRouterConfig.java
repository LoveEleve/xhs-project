package com.myxhs.ai.skeleton;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * TeamoRouter（OpenAI 兼容网关）模型配置。
 * key 只从环境变量 TEAMO_API_KEY 读取，绝不落库/硬编码。
 * DeepSeek 模型走 OpenAI 兼容格式（/v1/chat/completions）。
 */
public final class TeamoRouterConfig {

    /** OpenAI 兼容端点（OpenAI SDK 需要带 /v1） */
    public static final String BASE_URL = "https://api.teamorouter.com/v1";
    /** 付费档 deepseek-v4-flash（2026-08-10 起） */
    public static final String MODEL = "deepseek-v4-flash";

    private TeamoRouterConfig() {
    }

    /** 返回 TEAMO_API_KEY，未设置则抛异常（防止误跑） */
    public static String apiKey() {
        String key = System.getenv("TEAMO_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("环境变量 TEAMO_API_KEY 未设置；本项目密钥只从环境变量读取，不落库。");
        }
        return key;
    }

    public static ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey())
                .modelName(MODEL)
                .temperature(0.0)
                .build();
    }

    public static StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey())
                .modelName(MODEL)
                .temperature(0.0)
                .build();
    }
}
