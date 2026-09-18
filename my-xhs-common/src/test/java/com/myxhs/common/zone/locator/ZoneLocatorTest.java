package com.myxhs.common.zone.locator;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zone 自动发现定位器测试
 */
class ZoneLocatorTest {

    @Test
    void testFileLocator() throws Exception {
        Path file = Files.createTempFile("myxhs-zone", ".txt");
        Files.writeString(file, "  zone-b \n");
        assertEquals("zone-b", new FileZoneLocator(file.toString()).locate());

        Files.deleteIfExists(file);
        assertNull(new FileZoneLocator(file.toString()).locate());
        assertNull(new FileZoneLocator("").locate());
    }

    @Test
    void testIpRangeMatch() throws Exception {
        assertTrue(IpRangeZoneLocator.matches(InetAddress.getByName("192.168.0.142"), "192.168.0.0/24"));
        assertTrue(IpRangeZoneLocator.matches(InetAddress.getByName("10.1.2.3"), "10.0.0.0/8"));
        assertTrue(IpRangeZoneLocator.matches(InetAddress.getByName("127.0.0.1"), "127.0.0.1/32"));
        assertTrue(!IpRangeZoneLocator.matches(InetAddress.getByName("192.168.1.1"), "192.168.0.0/24"));
        assertTrue(!IpRangeZoneLocator.matches(InetAddress.getByName("192.168.0.1"), "bad-cidr"));
    }

    @Test
    void testIpRangeLocatorNoMatch() {
        IpRangeZoneLocator locator = new IpRangeZoneLocator(List.of("203.0.113.0/24=zone-x"));
        assertNull(locator.locate());
    }

    @Test
    void testCompositeOrderAndFallback() {
        CompositeZoneLocator composite = new CompositeZoneLocator(List.of(
                new ZoneLocator() {
                    @Override
                    public String locate() {
                        return null;
                    }

                    @Override
                    public int getOrder() {
                        return 1;
                    }
                },
                new ZoneLocator() {
                    @Override
                    public String locate() {
                        return "zone-a";
                    }

                    @Override
                    public int getOrder() {
                        return 2;
                    }
                }));
        assertEquals("zone-a", composite.locate());
    }
}
