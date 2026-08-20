package com.myxhs.ai.app.service.agent.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;

/**
 * Langfuse REST API SpanExporter（绕过 OTel SDK OTLP exporter 的路径限制）。
 *
 * 直接调用 Langfuse 的 OTLP 端点 POST /api/public/otel/v1/traces，
 * 使用标准 OTLP/JSON 格式。
 */
public class LangfuseOtlpExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(LangfuseOtlpExporter.class);
    private static final int TIMEOUT_S = 10;

    private final String endpoint;
    private final String authHeader;
    private final HttpClient http;
    private final ObjectMapper om;

    public LangfuseOtlpExporter(String endpoint, String publicKey, String secretKey, ObjectMapper om) {
        this.endpoint = endpoint.endsWith("/") ? endpoint + "v1/traces" : endpoint + "/v1/traces";
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((publicKey + ":" + secretKey).getBytes());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build();
        this.om = om;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        try {
            Collection<SpanData> filtered = spans.stream()
                    .filter(this::shouldExport)
                    .toList();
            if (filtered.isEmpty()) {
                return CompletableResultCode.ofSuccess();
            }
            String body = convertToOtlpJson(filtered);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(TIMEOUT_S))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.debug("[langfuse] export ok: {} spans, HTTP {}", filtered.size(), resp.statusCode());
                return CompletableResultCode.ofSuccess();
            }
            log.warn("[langfuse] export failed: HTTP {} body={}", resp.statusCode(),
                    resp.body() != null ? resp.body().substring(0, Math.min(200, resp.body().length())) : "");
            return CompletableResultCode.ofFailure();
        } catch (Exception e) {
            log.warn("[langfuse] export error: {}", e.getMessage());
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    /** Convert SpanData list to OTLP/JSON format */
    private String convertToOtlpJson(Collection<SpanData> spans) {
        try {
            ObjectNode root = om.createObjectNode();
            ArrayNode resourceSpots = root.putArray("resourceSpans");

            ObjectNode rs = resourceSpots.addObject();
            ObjectNode resource = rs.putObject("resource");
            ArrayNode attrs = resource.putArray("attributes");
            addAttribute(attrs, "service.name", "my-xhs-ai");

            ArrayNode scopeSpans = rs.putArray("scopeSpans");
            ObjectNode ss = scopeSpans.addObject();
            ArrayNode spanArray = ss.putArray("spans");

            for (SpanData sd : spans) {
                ObjectNode span = spanArray.addObject();
                span.put("traceId", sd.getTraceId());
                span.put("spanId", sd.getSpanId());
                if (sd.getParentSpanContext() != null && sd.getParentSpanContext().isValid()) {
                    span.put("parentSpanId", sd.getParentSpanContext().getSpanId());
                }
                span.put("name", sd.getName());
                span.put("kind", sd.getKind() == SpanKind.INTERNAL ? "SPAN_KIND_INTERNAL"
                        : sd.getKind() == SpanKind.CLIENT ? "SPAN_KIND_CLIENT" : "SPAN_KIND_SERVER");
                span.put("startTimeUnixNano", String.valueOf(sd.getStartEpochNanos()));
                span.put("endTimeUnixNano", String.valueOf(sd.getEndEpochNanos()));

                // Attributes
                ArrayNode spanAttrs = span.putArray("attributes");
                sd.getAttributes().forEach((key, value) -> {
                    if (value instanceof String s) addAttribute(spanAttrs, key.getKey(), s);
                    else if (value instanceof Long l) addAttribute(spanAttrs, key.getKey(), l);
                    else if (value instanceof Integer i) addAttribute(spanAttrs, key.getKey(), (long) i);
                    else if (value instanceof Double d) addAttribute(spanAttrs, key.getKey(), d);
                    else if (value instanceof Boolean b) addAttribute(spanAttrs, key.getKey(), b.toString());
                });

                // Status
                ObjectNode status = span.putObject("status");
                if (sd.getStatus().getStatusCode() == StatusCode.ERROR) {
                    status.put("code", "STATUS_CODE_ERROR");
                    status.put("message", sd.getStatus().getDescription() != null ? sd.getStatus().getDescription() : "");
                } else {
                    status.put("code", "STATUS_CODE_OK");
                }
            }

            return om.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[langfuse] OTLP JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private void addAttribute(ArrayNode attrs, String key, Object value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        ObjectNode val = attr.putObject("value");
        if (value instanceof String s) val.put("stringValue", s);
        else if (value instanceof Long l) val.put("intValue", String.valueOf(l));
        else if (value instanceof Double d) val.put("doubleValue", d);
        else val.put("stringValue", String.valueOf(value));
    }

    /** 过滤 HTTP 自动 tracing 噪音，仅保留 Agent 核心 span。 */
    private boolean shouldExport(SpanData sd) {
        String name = sd.getName();
        if (name == null) return false;
        return !name.startsWith("http ");
    }
}
