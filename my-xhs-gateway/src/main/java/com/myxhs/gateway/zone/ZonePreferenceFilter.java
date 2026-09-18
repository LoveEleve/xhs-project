package com.myxhs.gateway.zone;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.cloud.client.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关侧 Zone 优先过滤（WebFlux 独立实现，不依赖 common 模块）。
 * <p>同 zone 优先；同 zone 不足/不存在时回退全部实例。</p>
 *
 * @since 1.0.0
 */
public class ZonePreferenceFilter {

    public static final String METADATA_ZONE_KEY = "zone";
    public static final String DEFAULT_ZONE = "defaultZone";

    private final String zone;
    private final int sameZoneMinAvailable;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> gauges = new ConcurrentHashMap<>();

    public ZonePreferenceFilter(String zone, int sameZoneMinAvailable, MeterRegistry meterRegistry) {
        this.zone = zone;
        this.sameZoneMinAvailable = sameZoneMinAvailable;
        this.meterRegistry = meterRegistry;
    }

    public List<ServiceInstance> filter(List<ServiceInstance> instances) {
        int total = (instances == null) ? 0 : instances.size();
        if (total <= 1) {
            record(total, 0, "pass_through", "single_or_empty");
            return instances;
        }
        if (zone == null || zone.isBlank() || DEFAULT_ZONE.equals(zone)) {
            record(total, 0, "all", "invalid_zone");
            return instances;
        }
        List<ServiceInstance> sameZone = new ArrayList<>();
        for (ServiceInstance instance : instances) {
            Map<String, String> metadata = instance.getMetadata();
            String instanceZone = (metadata == null) ? null : metadata.get(METADATA_ZONE_KEY);
            if (zone.equals(instanceZone)) {
                sameZone.add(instance);
            }
        }
        if (sameZone.isEmpty()) {
            record(total, 0, "all", "no_same_zone");
            return instances;
        }
        if (sameZone.size() < sameZoneMinAvailable) {
            record(total, sameZone.size(), "all", "min_available");
            return instances;
        }
        record(total, sameZone.size(), "same_zone", "ok");
        return sameZone;
    }

    private void record(int total, int same, String decision, String reason) {
        if (meterRegistry == null) {
            return;
        }
        counters.computeIfAbsent(decision + '|' + reason + '|' + zone,
                        key -> Counter.builder("myxhs_zone_route_total")
                                .tags(Tags.of("zone", zone, "decision", decision, "reason", reason))
                                .register(meterRegistry))
                .increment();
        setGauge("total", total);
        setGauge("same", same);
    }

    private void setGauge(String kind, int value) {
        gauges.computeIfAbsent(kind + '|' + zone, key -> {
            AtomicInteger ref = new AtomicInteger();
            meterRegistry.gauge("myxhs_zone_instances", Tags.of("zone", zone, "kind", kind), ref, AtomicInteger::get);
            return ref;
        }).set(value);
    }
}
