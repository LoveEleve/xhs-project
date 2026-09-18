package com.myxhs.common.zone.metrics;

import com.myxhs.common.zone.ZoneContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Zone 路由决策指标：观测同 zone 命中与回退原因。
 * <p>
 * 指标：
 * <ul>
 *   <li>{@code myxhs_zone_route_total{zone,decision,reason}} — 每次实例列表过滤的决策计数</li>
 *   <li>{@code myxhs_zone_instances{zone,kind}} — 最近一次实例数量（kind=total|same）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class ZoneRouteMetrics {

    private final MeterRegistry registry;
    private final ZoneContext zoneContext;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> gauges = new ConcurrentHashMap<>();

    public ZoneRouteMetrics(MeterRegistry registry, ZoneContext zoneContext) {
        this.registry = registry;
        this.zoneContext = zoneContext;
    }

    public void record(int totalInstances, int sameZoneInstances, String decision, String reason) {
        if (registry == null) {
            return;
        }
        String zone = (zoneContext == null || zoneContext.getZone() == null) ? "unknown" : zoneContext.getZone();
        counters.computeIfAbsent(decision + '|' + reason + '|' + zone,
                        key -> Counter.builder("myxhs_zone_route_total")
                                .tags(Tags.of("zone", zone, "decision", decision, "reason", reason))
                                .register(registry))
                .increment();
        setGauge("total", zone, totalInstances);
        setGauge("same", zone, sameZoneInstances);
    }

    private void setGauge(String kind, String zone, int value) {
        gauges.computeIfAbsent(kind + '|' + zone, key -> {
            AtomicInteger ref = new AtomicInteger();
            registry.gauge("myxhs_zone_instances", Tags.of("zone", zone, "kind", kind), ref, AtomicInteger::get);
            return ref;
        }).set(value);
    }
}
