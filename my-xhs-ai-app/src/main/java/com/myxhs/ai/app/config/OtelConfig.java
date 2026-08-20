package com.myxhs.ai.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.tracing.LangfuseOtlpExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D6 Langfuse Trace：OTel SDK 配置。
 *
 * 使用自定义 LangfuseOtlpExporter（绕过 OTel SDK OTLP exporter 的路径限制）。
 * 读取环境变量：
 * - LANGFUSE_PUBLIC_KEY → Langfuse 公钥
 * - LANGFUSE_SECRET_KEY → Langfuse 私钥
 * - LANGFUSE_BASE_URL → Langfuse 端点（默认 https://cloud.langfuse.com）
 *
 * 未配置时降级为 no-op（不影响业务）。
 */
@Configuration
public class OtelConfig {

    private static final Logger log = LoggerFactory.getLogger(OtelConfig.class);

    @Bean
    public OpenTelemetry openTelemetry(
            @Value("${LANGFUSE_PUBLIC_KEY:}") String publicKey,
            @Value("${LANGFUSE_SECRET_KEY:}") String secretKey,
            @Value("${LANGFUSE_BASE_URL:https://cloud.langfuse.com}") String baseUrl,
            ObjectMapper om) {

        if (publicKey.isBlank() || secretKey.isBlank()) {
            log.info("[otel] Langfuse 未配置（LANGFUSE_PUBLIC_KEY/LANGFUSE_SECRET_KEY 为空），trace 降级为 no-op");
            return OpenTelemetry.noop();
        }

        try {
            LangfuseOtlpExporter exporter = new LangfuseOtlpExporter(
                    baseUrl + "/api/public/otel", publicKey, secretKey, om);

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();

            OpenTelemetry otel = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();

            log.info("[otel] Langfuse trace 初始化成功: endpoint={}", baseUrl + "/api/public/otel");
            return otel;
        } catch (Exception e) {
            log.warn("[otel] Langfuse trace 初始化失败，降级为 no-op: {}", e.getMessage());
            return OpenTelemetry.noop();
        }
    }

    @Bean
    public Tracer otelTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("my-xhs-ai");
    }
}
