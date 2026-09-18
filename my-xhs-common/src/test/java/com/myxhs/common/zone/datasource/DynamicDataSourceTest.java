package com.myxhs.common.zone.datasource;

import com.myxhs.common.zone.ZoneContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 动态 Zone 数据源测试（D3-9）
 */
class DynamicDataSourceTest {

    private DataSource dataSourceA;
    private DataSource dataSourceB;
    private DynamicDataSource dynamicDataSource;

    private static DataSource stubDataSource(Connection connection) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public Connection getConnection(String username, String password) {
                return connection;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return null;
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static Connection stubConnection() {
        return (Connection) Proxy.newProxyInstance(
                DynamicDataSourceTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isValid".equals(method.getName())) {
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    @BeforeEach
    void setUp() {
        ZoneContext.get().reset();
        dataSourceA = stubDataSource(stubConnection());
        dataSourceB = stubDataSource(stubConnection());
        Map<String, DataSource> zoneDataSources = new LinkedHashMap<>();
        zoneDataSources.put("zone-a", dataSourceA);
        zoneDataSources.put("zone-b", dataSourceB);
        dynamicDataSource = new DynamicDataSource(zoneDataSources, "zone-a");
        dynamicDataSource.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        dynamicDataSource.destroy();
        ZoneContext.get().reset();
    }

    @Test
    void testSwitchOnZoneChange() {
        assertSame(dataSourceA, dynamicDataSource.getDelegate());
        ZoneContext.get().setZone("zone-b");
        assertSame(dataSourceB, dynamicDataSource.getDelegate());
        assertEquals("zone-b", dynamicDataSource.getCurrentZone());
    }

    @Test
    void testUnknownZoneFallsBackToDefault() {
        ZoneContext.get().setZone("zone-x");
        assertSame(dataSourceA, dynamicDataSource.getDelegate());
    }

    @Test
    void testActiveConnectionCounting() throws SQLException {
        assertEquals(0, dynamicDataSource.getActiveConnectionCount());
        Connection connection = dynamicDataSource.getConnection();
        assertEquals(1, dynamicDataSource.getActiveConnectionCount());
        connection.close();
        assertEquals(0, dynamicDataSource.getActiveConnectionCount());
    }

    @Test
    void testDefaultZoneAliasFallsBackToDefault() {
        ZoneContext.get().setZone("defaultZone");
        assertSame(dataSourceA, dynamicDataSource.getDelegate());
    }
}
