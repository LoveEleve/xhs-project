package com.myxhs.common.zone.propagation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zone 传播入站过滤器：读取上游 X-Zone 头写入 {@link ZoneContextHolder}，请求结束清理。
 *
 * @since 1.0.0
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ZonePropagationFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public ZonePropagationFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String zone = request.getHeader(ZoneContextHolder.ZONE_HEADER);
        if (zone != null && !zone.isBlank()) {
            ZoneContextHolder.set(zone);
            record("in", zone.trim());
            log.debug("[ZonePropagation] 收到上游 Zone: from={}, uri={}", zone, request.getRequestURI());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            ZoneContextHolder.clear();
        }
    }

    private void record(String direction, String zone) {
        if (meterRegistry == null) {
            return;
        }
        counters.computeIfAbsent(direction + '|' + zone,
                        key -> Counter.builder("myxhs_zone_propagation_total")
                                .tags(Tags.of("direction", direction, "zone", zone))
                                .register(meterRegistry))
                .increment();
    }
}
