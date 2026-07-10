package com.myxhs.common.zone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZoneContext 单元测试
 */
class ZoneContextTest {

    private ZoneContext zoneContext;

    @BeforeEach
    void setUp() {
        zoneContext = ZoneContext.get();
        zoneContext.reset();
    }

    @Test
    void testSingletonInstance() {
        ZoneContext instance1 = ZoneContext.get();
        ZoneContext instance2 = ZoneContext.get();
        assertSame(instance1, instance2, "ZoneContext should be singleton");
    }

    @Test
    void testDefaultValues() {
        assertTrue(zoneContext.isEnabled(), "Default enabled should be true");
        assertEquals("defaultZone", zoneContext.getZone(), "Default zone should be defaultZone");
        assertFalse(zoneContext.isPreferenceEnabled(), "Default preferenceEnabled should be false");
        assertEquals(10, zoneContext.getPreferenceFilterOrder(), "Default filter order should be 10");
        assertEquals(100, zoneContext.getPreferenceUpstreamZoneReadyPercentage());
        assertEquals(5, zoneContext.getPreferenceUpstreamSameZoneMinAvailable());
        assertNull(zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testSetZone() {
        zoneContext.setZone("zone-shanghai");
        assertEquals("zone-shanghai", zoneContext.getZone());
    }

    @Test
    void testSetEnabled() {
        zoneContext.setEnabled(false);
        assertFalse(zoneContext.isEnabled());
    }

    @Test
    void testSetPreferenceEnabled() {
        zoneContext.setPreferenceEnabled(true);
        assertTrue(zoneContext.isPreferenceEnabled());
    }

    @Test
    void testSetPreferenceFilterOrder() {
        zoneContext.setPreferenceFilterOrder(5);
        assertEquals(5, zoneContext.getPreferenceFilterOrder());
    }

    @Test
    void testSetPreferenceUpstreamZoneReadyPercentage() {
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(80);
        assertEquals(80, zoneContext.getPreferenceUpstreamZoneReadyPercentage());
    }

    @Test
    void testSetPreferenceUpstreamSameZoneMinAvailable() {
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(3);
        assertEquals(3, zoneContext.getPreferenceUpstreamSameZoneMinAvailable());
    }

    @Test
    void testSetPreferenceUpstreamDisabledZone() {
        zoneContext.setPreferenceUpstreamDisabledZone("zone1,zone2");
        assertEquals("zone1,zone2", zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testSetPreferenceUpstreamDisabledZoneWithSpaces() {
        zoneContext.setPreferenceUpstreamDisabledZone(" zone1 , zone2 ");
        assertEquals("zone1,zone2", zoneContext.getPreferenceUpstreamDisabledZone());
    }

    @Test
    void testPropertyChangeListener() {
        List<PropertyChangeEvent> events = new ArrayList<>();
        PropertyChangeListener listener = events::add;
        zoneContext.addPropertyChangeListener(listener);

        zoneContext.setZone("zone-beijing");

        assertEquals(1, events.size());
        assertEquals("zone", events.get(0).getPropertyName());
        assertEquals("defaultZone", events.get(0).getOldValue());
        assertEquals("zone-beijing", events.get(0).getNewValue());

        zoneContext.removePropertyChangeListener(listener);
    }

    @Test
    void testPropertyChangeListenerNotFiredWhenNoChange() {
        AtomicReference<PropertyChangeEvent> eventRef = new AtomicReference<>();
        PropertyChangeListener listener = eventRef::set;
        zoneContext.addPropertyChangeListener(listener);

        // Set same value should not fire
        zoneContext.setZone("defaultZone");

        assertNull(eventRef.get());
        zoneContext.removePropertyChangeListener(listener);
    }

    @Test
    void testEnable() {
        zoneContext.setEnabled(false);
        boolean previous = zoneContext.enable();
        assertFalse(previous, "Previous state should be false");
        assertTrue(zoneContext.isEnabled(), "Should be enabled after enable()");
    }

    @Test
    void testReset() {
        zoneContext.setZone("zone-shanghai");
        zoneContext.setEnabled(false);
        zoneContext.setPreferenceEnabled(true);
        zoneContext.setPreferenceFilterOrder(20);

        zoneContext.reset();

        assertEquals("defaultZone", zoneContext.getZone());
        assertTrue(zoneContext.isEnabled());
        assertFalse(zoneContext.isPreferenceEnabled());
        assertEquals(10, zoneContext.getPreferenceFilterOrder());
    }

    @Test
    void testGetCurrentZone() {
        String currentZone = ZoneContext.getCurrentZone();
        assertEquals("defaultZone", currentZone);
    }

    @Test
    void testGetCurrentZoneFromSystemProperty() {
        System.setProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME, "zone-shenzhen");
        try {
            assertEquals("zone-shenzhen", ZoneContext.getCurrentZone());
        } finally {
            System.clearProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME);
        }
    }
}
