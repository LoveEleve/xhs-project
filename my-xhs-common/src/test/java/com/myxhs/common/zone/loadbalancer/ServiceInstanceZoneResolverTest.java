package com.myxhs.common.zone.loadbalancer;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 实例 Zone 解析测试（标准键 zone 优先，兼容 myxhs.availability.zone）
 */
class ServiceInstanceZoneResolverTest {

    private DefaultServiceInstance instance() {
        return new DefaultServiceInstance("i1", "my-xhs-product", "127.0.0.1", 19006, false);
    }

    @Test
    void testResolveStandardZoneKey() {
        DefaultServiceInstance instance = instance();
        instance.getMetadata().put("zone", "zone-a");
        assertEquals("zone-a", ServiceInstanceZoneResolver.INSTANCE.resolve(instance));
    }

    @Test
    void testResolveLegacyZoneKey() {
        DefaultServiceInstance instance = instance();
        instance.getMetadata().put("myxhs.availability.zone", "zone-b");
        assertEquals("zone-b", ServiceInstanceZoneResolver.INSTANCE.resolve(instance));
    }

    @Test
    void testStandardKeyWins() {
        DefaultServiceInstance instance = instance();
        instance.getMetadata().put("zone", "zone-a");
        instance.getMetadata().put("myxhs.availability.zone", "zone-b");
        assertEquals("zone-a", ServiceInstanceZoneResolver.INSTANCE.resolve(instance));
    }

    @Test
    void testResolveMissingZone() {
        assertNull(ServiceInstanceZoneResolver.INSTANCE.resolve(instance()));
        assertNull(ServiceInstanceZoneResolver.INSTANCE.resolve(null));
    }
}
