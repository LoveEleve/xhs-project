package com.myxhs.common.zone.redis.config;

import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Zone 感知 Redis ReadFrom 解析测试
 */
class ZoneRedisReadFromResolverTest {

    @Test
    void testSlaveZonePrefersReplica() {
        assertEquals(ReadFrom.REPLICA_PREFERRED,
                ZoneRedisReadFromResolver.resolve(true, "zone-b", "zone-b"));
    }

    @Test
    void testMasterZoneUsesMaster() {
        assertEquals(ReadFrom.MASTER, ZoneRedisReadFromResolver.resolve(true, "zone-a", "zone-b"));
    }

    @Test
    void testDisabledOrUnknownZoneUsesMaster() {
        assertEquals(ReadFrom.MASTER, ZoneRedisReadFromResolver.resolve(false, "zone-b", "zone-b"));
        assertEquals(ReadFrom.MASTER, ZoneRedisReadFromResolver.resolve(true, "zone-c", "zone-b"));
        assertEquals(ReadFrom.MASTER, ZoneRedisReadFromResolver.resolve(true, "defaultZone", "zone-b"));
        assertEquals(ReadFrom.MASTER, ZoneRedisReadFromResolver.resolve(true, null, "zone-b"));
    }

    @Test
    void testBlankSlaveZoneUsesMaster() {
        assertEquals(ReadFrom.MASTER, ZoneRedisReadFromResolver.resolve(true, "zone-b", ""));
    }
}
