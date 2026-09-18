package com.myxhs.common.zone;

import com.myxhs.common.zone.metrics.ZoneRouteMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Zone 路由指标测试
 */
class ZoneRouteMetricsTest {

    private ZoneContext zoneContext;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
        registry = new SimpleMeterRegistry();
    }

    private ZonePreferenceFilter<MockZoneEntity> filter() {
        return new ZonePreferenceFilter<>(zoneContext, MockZoneEntity::getZone, new ZoneRouteMetrics(registry, zoneContext));
    }

    private double count(String decision, String reason) {
        var counter = registry.find("myxhs_zone_route_total").tags("decision", decision, "reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String kind) {
        return registry.find("myxhs_zone_instances").tags("kind", kind).gauge().value();
    }

    @Test
    void testSameZoneDecisionRecorded() {
        zoneContext.setZone("zone-a");
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);
        List<MockZoneEntity> result = filter().filter(List.of(
                new MockZoneEntity("zone-a", "s1"), new MockZoneEntity("zone-b", "s2")));
        assertEquals(1, result.size());
        assertEquals(1.0, count("same_zone", "ok"));
        assertEquals(2.0, gauge("total"));
        assertEquals(1.0, gauge("same"));
    }

    @Test
    void testMinAvailableFallbackRecorded() {
        zoneContext.setZone("zone-a");
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(5);
        List<MockZoneEntity> result = filter().filter(List.of(
                new MockZoneEntity("zone-a", "s1"), new MockZoneEntity("zone-b", "s2")));
        assertEquals(2, result.size());
        assertEquals(1.0, count("all", "min_available"));
    }

    @Test
    void testNoSameZoneFallbackRecorded() {
        zoneContext.setZone("zone-a");
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);
        List<MockZoneEntity> result = filter().filter(List.of(
                new MockZoneEntity("zone-b", "s1"), new MockZoneEntity("zone-c", "s2")));
        assertEquals(2, result.size());
        assertEquals(1.0, count("all", "no_same_zone"));
        assertEquals(0.0, gauge("same"));
    }

    static class MockZoneEntity {
        private final String zone;
        private final String name;

        MockZoneEntity(String zone, String name) {
            this.zone = zone;
            this.name = name;
        }

        String getZone() {
            return zone;
        }

        String getName() {
            return name;
        }
    }
}
