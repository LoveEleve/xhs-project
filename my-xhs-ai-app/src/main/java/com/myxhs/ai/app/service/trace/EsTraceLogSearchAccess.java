package com.myxhs.ai.app.service.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EsTraceLogSearchAccess implements TraceLogSearchAccess {

    private final ObjectMapper om;
    private final HttpClient http;
    private final String esUrl;
    private final String authHeader;
    private final String indexPattern;
    private final int maxEvents;

    public EsTraceLogSearchAccess(ObjectMapper om,
                                  @Value("${myxhs.ai.rag.es-url:http://21.130.247.89:19200}") String esUrl,
                                  @Value("${myxhs.ai.rag.es-user:elastic}") String esUser,
                                  @Value("${myxhs.ai.rag.es-pass:}") String esPass,
                                  @Value("${myxhs.ai.trace-search.es-index:myxhs-logs-*}") String indexPattern,
                                  @Value("${myxhs.ai.trace-search.max-events:200}") int maxEvents) {
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.esUrl = esUrl;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((esUser + ":" + esPass).getBytes(StandardCharsets.UTF_8));
        this.indexPattern = indexPattern;
        this.maxEvents = maxEvents;
    }

    @Override
    public TraceSearchResult searchByTraceId(String traceId) {
        try {
            JsonNode root = om.readTree(es("POST", "/" + indexPattern + "/_search", buildSearchBody(traceId)));
            Map<String, Integer> byService = new LinkedHashMap<>();
            List<TraceSearchResult.TraceEvent> timeline = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                String service = text(src, "APP_NAME", "service", "application", "app");
                if (service == null || service.isBlank()) {
                    service = "unknown-service";
                }
                byService.merge(service, 1, Integer::sum);
                timeline.add(new TraceSearchResult.TraceEvent(
                        src.path("@timestamp").asText(""),
                        service,
                        src.path("level").asText(""),
                        src.path("message").asText("")));
            }
            timeline.sort(java.util.Comparator.comparing(
                    TraceSearchResult.TraceEvent::timestamp,
                    this::compareTimestamp));
            String entryService = timeline.isEmpty() ? null : timeline.get(0).service();
            String lastService = logicalLastService(timeline);
            List<TraceSearchResult.ServiceHit> hits = new ArrayList<>();
            for (Map.Entry<String, Integer> e : byService.entrySet()) {
                hits.add(new TraceSearchResult.ServiceHit(e.getKey(), TraceServiceLayerMapper.layerOf(e.getKey()), e.getValue()));
            }
            return new TraceSearchResult(traceId, "remote-es", hits, List.of(), List.of(), timeline, entryService, lastService);
        } catch (Exception e) {
            return new TraceSearchResult(traceId, "remote-es-error", List.of(), List.of(), List.of("es-query-failed"), List.of(), null, null);
        }
    }

    private String buildSearchBody(String traceId) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("size", maxEvents);
        ArrayNode sort = body.putArray("sort");
        sort.addObject().putObject("@timestamp").put("order", "asc");
        ArrayNode source = body.putArray("_source");
        source.add("@timestamp");
        source.add("APP_NAME");
        source.add("service");
        source.add("application");
        source.add("app");
        source.add("level");
        source.add("message");
        source.add("traceId");
        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.addObject().putObject("term").put("traceId", traceId);
        return om.writeValueAsString(body);
    }

    private static String logicalLastService(List<TraceSearchResult.TraceEvent> timeline) {
        for (int i = timeline.size() - 1; i >= 0; i--) {
            String service = timeline.get(i).service();
            if (!service.contains("gateway")) {
                return service;
            }
        }
        return timeline.isEmpty() ? null : timeline.get(timeline.size() - 1).service();
    }

    private int compareTimestamp(String left, String right) {
        try {
            return Instant.parse(left).compareTo(Instant.parse(right));
        } catch (Exception ignored) {
            if (left == null || left.isBlank()) {
                return right == null || right.isBlank() ? 0 : 1;
            }
            if (right == null || right.isBlank()) {
                return -1;
            }
            return left.compareTo(right);
        }
    }

    private String text(JsonNode src, String... names) {
        for (String name : names) {
            String value = src.path(name).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String es(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(esUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            b.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.GET();
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException("ES " + method + " " + path + " failed: " + r.statusCode());
        }
        return r.body();
    }
}
