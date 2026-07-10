package com.myxhs.common.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZoneConstants 测试
 */
class ZoneConstantsTest {

    @Test
    void testDefaultValues() {
        assertTrue(ZoneConstants.DEFAULT_ZONE_ENABLED);
        assertEquals("defaultZone", ZoneConstants.DEFAULT_ZONE);
        assertEquals(10, ZoneConstants.DEFAULT_ZONE_PREFERENCE_FILTER_ORDER);
        assertEquals(100, ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE);
        assertEquals(5, ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE);
        assertNull(ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE);
    }

    @Test
    void testPropertyNames() {
        assertEquals("myxhs.availability.zone", ZoneConstants.ZONE_PROPERTY_NAME);
        assertEquals("myxhs.availability.zone.enabled", ZoneConstants.ZONE_ENABLED_PROPERTY_NAME);
        assertEquals("myxhs.current.availability.zone", ZoneConstants.CURRENT_ZONE_PROPERTY_NAME);
        assertEquals("myxhs.availability.zone.preference.enabled", ZoneConstants.PREFERENCE_ENABLED_PROPERTY_NAME);
        assertEquals("myxhs.availability.zone.preference.filter.order", ZoneConstants.PREFERENCE_FILTER_ORDER_PROPERTY_NAME);
    }
}
