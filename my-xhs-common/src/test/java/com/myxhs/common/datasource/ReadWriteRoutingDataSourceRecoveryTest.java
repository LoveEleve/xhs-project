package com.myxhs.common.datasource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 从库故障降级 + 自动恢复测试
 */
class ReadWriteRoutingDataSourceRecoveryTest {

    private final AtomicBoolean slaveAvailable = new AtomicBoolean(false);
    private final Connection masterConn = stubConnection();
    private final Connection slaveConn = stubConnection();
    private ReadWriteRoutingDataSource dataSource;

    static class ExposedDataSource extends ReadWriteRoutingDataSource {
        Object lookup() {
            return determineCurrentLookupKey();
        }
    }

    private static Connection stubConnection() {
        return (Connection) Proxy.newProxyInstance(
                ReadWriteRoutingDataSourceRecoveryTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isValid".equals(method.getName())) {
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private DataSource stub(AtomicBoolean available, Connection conn) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                if (!available.get()) {
                    throw new SQLException("connection refused");
                }
                return conn;
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
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

    @BeforeEach
    void setUp() {
        dataSource = new ExposedDataSource();
        dataSource.setSlaveCheckIntervalMs(0);
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.MASTER, stub(new AtomicBoolean(true), masterConn));
        targets.put(DataSourceType.SLAVE, stub(slaveAvailable, slaveConn));
        dataSource.setTargetDataSources(targets);
    }

    private Connection read() throws SQLException {
        DataSourceContextHolder.set(DataSourceType.SLAVE);
        return dataSource.getConnection();
    }

    @Test
    void testFallbackToMasterThenRecoverSlave() throws SQLException {
        assertSame(masterConn, read(), "从库不可用时应降级主库");

        slaveAvailable.set(true);
        assertSame(slaveConn, read(), "从库恢复后应自动回切（原 bug：永不恢复）");
    }
}
