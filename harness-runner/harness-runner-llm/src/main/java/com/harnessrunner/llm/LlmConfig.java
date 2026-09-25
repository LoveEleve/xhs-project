package com.harnessrunner.llm;

import java.time.Duration;
import java.util.Optional;

public record LlmConfig(String baseUrl, String apiKey, String model, Duration timeout) {

    public LlmConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    public static Optional<LlmConfig> fromEnvironment() {
        String apiKey = System.getenv("HARNESS_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new LlmConfig(
                environment("HARNESS_LLM_BASE_URL", "https://api.deepseek.com"),
                apiKey,
                environment("HARNESS_LLM_MODEL", "deepseek-chat"),
                Duration.ofSeconds(Long.parseLong(environment("HARNESS_LLM_TIMEOUT_SECONDS", "60")))));
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
