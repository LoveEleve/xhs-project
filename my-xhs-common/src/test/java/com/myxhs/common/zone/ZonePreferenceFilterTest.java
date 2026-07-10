package com.myxhs.common.zone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZonePreferenceFilter 核心路由算法测试
 */
class ZonePreferenceFilterTest {

    private ZoneContext zoneContext;
    private ZonePreferenceFilter<MockEntity> filter;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
        filter = new ZonePreferenceFilter<>(zoneContext, MockEntity::getZone);
    }

    // ---- 边界情况 ----

    @Test
    void testFilterNullList() {
        List<MockEntity> result = filter.filter(null);
        assertNull(result, "Null input should return null");
    }

    @Test
    void testFilterEmptyList() {
        List<MockEntity> result = filter.filter(Collections.emptyList());
        assertTrue(result.isEmpty(), "Empty input should return empty");
    }

    @Test
    void testFilterSingleElement() {
        List<MockEntity> entities = List.of(new MockEntity("zone1", "svc1"));
        List<MockEntity> result = filter.filter(entities);
        assertSame(entities, result, "Single element should return same list");
        assertEquals(1, result.size());
    }

    // ---- 功能禁用情况 ----

    @Test
    void testFilterWhenZoneDisabled() {
        zoneContext.setEnabled(false);
        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);
        assertEquals(entities.size(), result.size(), "All entities should be returned when zone is disabled");
    }

    @Test
    void testFilterWhenPreferenceDisabled() {
        zoneContext.setPreferenceEnabled(false);
        zoneContext.setZone("zone1");
        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);
        assertEquals(entities.size(), result.size(), "All entities should be returned when preference is disabled");
    }

    @Test
    void testFilterWhenZoneIsDefault() {
        zoneContext.setZone("defaultZone");
        zoneContext.setPreferenceEnabled(true);
        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);
        assertEquals(entities.size(), result.size(), "All entities should be returned when zone is default");
    }

    // ---- 核心同 Zone 优先逻辑 ----

    @Test
    void testFilterSameZonePreference() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone1");

        List<MockEntity> entities = createMixedZoneEntities();
        // zone1: 3 entities, zone2: 2 entities, null: 1 entity
        // total: 6, zoneCount: 5, sameZoneMinAvailable: 5 (default)
        // sameZoneEntities.size() = 3 < 5 → fallback to all

        List<MockEntity> result = filter.filter(entities);
        assertEquals(entities.size(), result.size(),
                "Should fallback to all when same-zone count under threshold");
    }

    @Test
    void testFilterSameZonePreferenceWithLowerThreshold() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone1");
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(3);
        // 降低 upstream ready 百分比阈值，因为有一个 null-zone 实体
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(80);

        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);

        assertEquals(3, result.size(), "Should return only zone1 entities");
        assertTrue(result.stream().allMatch(e -> "zone1".equals(e.getZone())),
                "All results should be from zone1");
    }

    @Test
    void testFilterNoSameZoneEntity() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone3");  // No entity in zone3
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);

        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);

        assertEquals(entities.size(), result.size(),
                "Should return all entities when no same-zone entity found");
    }

    // ---- 禁用 Zone 过滤 ----

    @Test
    void testFilterDisabledZone() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone1");
        zoneContext.setPreferenceUpstreamDisabledZone("zone2");
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);
        // 禁用 zone2 后剩余 zone1×3 + null×1 = 4, zoneCount=3, 3*100/4=75%
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(75);

        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);

        assertTrue(result.stream().noneMatch(e -> "zone2".equals(e.getZone())),
                "No zone2 entities should be present");
        assertTrue(result.stream().allMatch(e -> "zone1".equals(e.getZone())),
                "All results should be from zone1");
    }

    @Test
    void testFilterDisabledZoneNotEnoughAfterFilter() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone1");
        zoneContext.setPreferenceUpstreamDisabledZone("zone1,zone2");
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);

        // zone1 and zone2 disabled, only null-zone entities remain
        List<MockEntity> entities = createMixedZoneEntities();
        List<MockEntity> result = filter.filter(entities);

        assertEquals(entities.size(), result.size(),
                "Should return all when not enough after disabled zone filter");
    }

    // ---- 上游就绪百分比 ----

    @Test
    void testFilterUpstreamNotReady() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone1");
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(100);  // Requires 100% ready
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);

        // Create entities where some have null zone (not ready)
        List<MockEntity> entities = new ArrayList<>();
        entities.add(new MockEntity("zone1", "svc1"));
        entities.add(new MockEntity("zone1", "svc2"));
        entities.add(new MockEntity(null, "svc3"));    // null zone = not ready
        entities.add(new MockEntity(null, "svc4"));    // null zone = not ready
        entities.add(new MockEntity("zone2", "svc5"));

        // zoneCount = 3, totalSize = 5, readyPercentage = 3*100/5 = 60% < 100%
        List<MockEntity> result = filter.filter(entities);
        assertEquals(entities.size(), result.size(),
                "Should return all when upstream ready percentage under threshold");
    }

    // ---- 全部同 Zone ----

    @Test
    void testFilterAllSameZone() {
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setZone("zone1");
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(1);

        List<MockEntity> entities = List.of(
                new MockEntity("zone1", "svc1"),
                new MockEntity("zone1", "svc2"),
                new MockEntity("zone1", "svc3")
        );

        List<MockEntity> result = filter.filter(entities);
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(e -> "zone1".equals(e.getZone())));
    }

    @Test
    void testGetOrder() {
        assertEquals(10, filter.getOrder());
        zoneContext.setPreferenceFilterOrder(5);
        assertEquals(5, filter.getOrder());
    }

    // ---- 辅助方法 ----

    private List<MockEntity> createMixedZoneEntities() {
        return new ArrayList<>(List.of(
                new MockEntity("zone1", "service-a"),
                new MockEntity("zone1", "service-b"),
                new MockEntity("zone1", "service-c"),
                new MockEntity("zone2", "service-d"),
                new MockEntity("zone2", "service-e"),
                new MockEntity(null, "service-f")   // 无 zone 信息
        ));
    }

    /**
     * 模拟实体类
     */
    static class MockEntity {
        private final String zone;
        private final String name;

        MockEntity(String zone, String name) {
            this.zone = zone;
            this.name = name;
        }

        String getZone() {
            return zone;
        }

        String getName() {
            return name;
        }

        @Override
        public String toString() {
            return "MockEntity{zone='" + zone + "', name='" + name + "'}";
        }
    }
}
