package com.harnessrunner.llm;

public record LlmResponse(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs) {

    public LlmResponse {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
    }
}
