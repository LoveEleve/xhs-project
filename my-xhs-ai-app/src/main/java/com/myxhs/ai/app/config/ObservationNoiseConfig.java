package com.myxhs.ai.app.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D6 Langfuse Trace 降噪：关闭 Spring MVC 自动 HTTP tracing，Langfuse 仅保留 Agent 核心 span。
 *
 * 否则 Dashboard 会被 `/api/runs` 和 `/api/runs/{runId}` 的轮询 HTTP trace 淹没，
 * 影响 Agent trace 的可读性。基础设施层 HTTP trace 仍由 SkyWalking 保留。
 */
@Configuration
public class ObservationNoiseConfig {

    @Bean
    public ObservationPredicate observationNoisePredicate() {
        return (name, context) -> {
            // 过滤 Spring MVC 默认 HTTP server observation
            if (name != null && (name.startsWith("http.server.requests") || name.startsWith("http.client.requests"))) {
                return false;
            }
            return true;
        };
    }
}
