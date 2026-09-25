package com.harnessrunner.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig config;
    private final HttpClient http;

    public OpenAiCompatibleClient(LlmConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.model());
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        body.putObject("response_format").put("type", "json_object");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(config.baseUrl()) + "/chat/completions"))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("LLM 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 请求被中断", e);
        }
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;

        if (response.statusCode() / 100 != 2) {
            throw new LlmException("LLM 返回 HTTP " + response.statusCode() + ": " + excerpt(response.body()));
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new LlmException("LLM 响应不是合法 JSON: " + excerpt(response.body()), e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new LlmException("LLM 响应缺少 choices[0].message.content: " + excerpt(response.body()));
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(content.asText(),
                root.path("model").asText(config.model()),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                latencyMs);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String excerpt(String body) {
        String text = body == null ? "" : body.strip();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
