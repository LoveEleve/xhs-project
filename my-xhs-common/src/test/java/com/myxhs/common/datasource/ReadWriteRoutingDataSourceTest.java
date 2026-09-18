package com.myxhs.common.datasource;

import com.myxhs.common.zone.ZoneConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Zone 感知数据源路由测试
 */
class ReadWriteRoutingDataSourceTest {

    private ReadWriteRoutingDataSource dataSource;

    static class ExposedDataSource extends ReadWriteRoutingDataSource {
        Object lookup() {
            return determineCurrentLookupKey();
        }
    }

    @BeforeEach
    void setUp() {
        ExposedDataSource ds = new ExposedDataSource();
        ds.setZoneRoutingEnabled(true);
        ds.setMasterZone("zone-a");
        ds.setSlaveZone("zone-b");
        dataSource = ds;
        System.clearProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME);
    }

    private Object lookup(DataSourceType manual, String zone) {
        if (zone != null) {
            System.setProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME, zone);
        }
        DataSourceContextHolder.set(manual);
        return ((ExposedDataSource) dataSource).lookup();
    }

    @Test
    void testDisabledKeepsLegacyRouting() {
        dataSource = new ExposedDataSource();
        assertEquals(DataSourceType.SLAVE, lookup(DataSourceType.SLAVE, "zone-a"));
    }

    @Test
    void testMasterZoneReadsLocalMaster() {
        assertEquals(DataSourceType.MASTER, lookup(DataSourceType.SLAVE, "zone-a"));
        assertEquals(DataSourceType.MASTER, lookup(DataSourceType.MASTER, "zone-a"));
    }

    @Test
    void testSlaveZoneReadsLocalSlave() {
        assertEquals(DataSourceType.SLAVE, lookup(DataSourceType.SLAVE, "zone-b"));
        assertEquals(DataSourceType.MASTER, lookup(DataSourceType.MASTER, "zone-b"));
    }

    @Test
    void testUnknownZoneKeepsLegacy() {
        assertEquals(DataSourceType.SLAVE, lookup(DataSourceType.SLAVE, "zone-c"));
        assertEquals(DataSourceType.SLAVE, lookup(DataSourceType.SLAVE, "defaultZone"));
        assertEquals(DataSourceType.SLAVE, lookup(DataSourceType.SLAVE, null));
    }
}
