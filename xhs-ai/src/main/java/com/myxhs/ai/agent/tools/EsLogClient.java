package com.myxhs.ai.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 简化版 ES 日志查询客户端（RV21）：只用 REST，无 DSL 暴露给模型。
 * 索引固定 myxhs-logs-*，鉴权复用 MYXHS_ES_* 环境变量。
 */
@Slf4j
@Component
public class EsLogClient {

    public static final String INDEX = "myxhs-logs-*";

    @Value("${MYXHS_ES_URL:http://127.0.0.1:19200}")
    private String baseUrl;

    @Value("${MYXHS_ES_USER:}")
    private String user;

    @Value("${MYXHS_ES_PASSWORD:}")
    private String password;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public String getJson(String path) throws Exception {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("ES 请求失败 HTTP " + response.statusCode());
        }
        return response.body();
    }

    public String search(String bodyJson) throws Exception {
        return searchIndex(INDEX, bodyJson);
    }

    public String searchIndex(String index, String bodyJson) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + index + "/_search"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("ES 查询失败 HTTP " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(200, response.body().length())));
        }
        return response.body();
    }
}
