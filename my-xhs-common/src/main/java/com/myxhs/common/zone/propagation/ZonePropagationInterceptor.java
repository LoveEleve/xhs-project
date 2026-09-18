package com.myxhs.common.zone.propagation;

import com.myxhs.common.zone.ZoneConstants;
import com.myxhs.common.zone.ZoneContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zone 传播出站拦截器（Feign）：为本机发起的调用附加 X-Zone 头（优先请求级 holder，其次本机 Zone）。
 *
 * @since 1.0.0
 */
@Slf4j
public class ZonePropagationInterceptor implements RequestInterceptor {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public ZonePropagationInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void apply(RequestTemplate template) {
        String zone = ZoneContextHolder.get();
        if (zone == null || zone.isBlank()) {
            zone = ZoneContext.get().getZone();
        }
        if (zone == null || zone.isBlank() || ZoneConstants.DEFAULT_ZONE.equals(zone)) {
            return;
        }
        if (template.headers().containsKey(ZoneContextHolder.ZONE_HEADER)) {
            return;
        }
        template.header(ZoneContextHolder.ZONE_HEADER, zone);
        record(zone);
    }

    private void record(String zone) {
        if (meterRegistry == null) {
            return;
        }
        counters.computeIfAbsent(zone,
                        key -> Counter.builder("myxhs_zone_propagation_total")
                                .tags(Tags.of("direction", "out", "zone", zone))
                                .register(meterRegistry))
                .increment();
    }
}
