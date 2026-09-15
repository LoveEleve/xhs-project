package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Prometheus 查询客户端（RV22）：instant / range 两个只读接口，凭据复用 MYXHS_PROM_URL。
 */
@Slf4j
@Component
public class PrometheusClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${MYXHS_PROM_URL:http://127.0.0.1:19090}")
    private String baseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public JsonNode query(String promql) throws Exception {
        return get("/api/v1/query?query=" + encode(promql));
    }

    public JsonNode queryRange(String promql, long startEpochSec, long endEpochSec, int stepSec) throws Exception {
        return get("/api/v1/query_range?query=" + encode(promql)
                + "&start=" + startEpochSec + "&end=" + endEpochSec + "&step=" + stepSec);
    }

    private JsonNode get(String path) throws Exception {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Prometheus 查询失败 HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
