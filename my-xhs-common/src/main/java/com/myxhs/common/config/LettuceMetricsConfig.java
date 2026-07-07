package com.myxhs.common.config;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce Redis 客户端指标集成
 *
 * 通过自定义 ClientResources 注入 MicrometerCommandLatencyRecorder，
 * Spring Data Redis 会自动发现并使用此 Bean，从而记录 Redis 命令的延迟指标。
 *
 * 指标示例：
 * - lettuce.command.latency{command=GET, remote=16379} → Timer
 * - lettuce.command.count{command=SET, remote=16381} → Counter
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "io.lettuce.core.resource.ClientResources")
public class LettuceMetricsConfig {

    @Bean(destroyMethod = "shutdown")
    public ClientResources lettuceClientResources(MeterRegistry meterRegistry) {
        MicrometerOptions options = MicrometerOptions.builder()
                .histogram(true)
                .build();
        MicrometerCommandLatencyRecorder recorder = new MicrometerCommandLatencyRecorder(meterRegistry, options);
        log.info("[Lettuce] 已启用 MicrometerCommandLatencyRecorder 延迟指标采集");
        return DefaultClientResources.builder()
                .commandLatencyRecorder(recorder)
                .build();
    }
}
