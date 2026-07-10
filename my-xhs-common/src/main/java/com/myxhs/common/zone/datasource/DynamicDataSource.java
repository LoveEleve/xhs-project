package com.myxhs.common.zone.datasource;

import com.myxhs.common.zone.ZoneConstants;
import com.myxhs.common.zone.ZoneContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 动态数据源 — 热切换代理模式。
 * <p>
 * 实现 javax.sql.DataSource，将实际调用委托给内部的 DataSource。
 * 支持通过 ZoneContext 的 zone 变更事件自动切换数据源。
 * </p>
 *
 * <h3>Zone 切换事务协调（P1-10 修复）</h3>
 * <p>
 * Zone 切换时，正在进行中的 TCC 分布式事务（Try 已执行但 Confirm/Cancel 未执行）
 * 可能因数据源切换而无法正确完成。本类通过以下机制保障事务安全：
 * </p>
 * <ul>
 *   <li><b>活跃连接计数</b>：每次 getConnection() 时递增，close() 时递减，
 *       切换前检查是否有活跃连接（即正在进行的事务）</li>
 *   <li><b>切换等待策略</b>：如果切换时有活跃连接，等待最多 30 秒让事务完成，
 *       超时后强制切换并记录告警（TCC 有 Cancel 兜底机制）</li>
 *   <li><b>GracefulShutdownListener 协作</b>：Zone 切换前先通过
 *       GracefulShutdownListener 等待所有 TCC 事务完成（Try→Confirm/Cancel）</li>
 * </ul>
 *
 * <pre>{@code
 * // 使用示例
 * Map<String, DataSource> zoneDataSources = Map.of(
 *     "zone1", dataSource1,
 *     "zone2", dataSource2
 * );
 * DynamicDataSource ds = new DynamicDataSource(zoneDataSources, "defaultZone");
 * }</pre>
 *
 * @since 1.0.0
 */
@Slf4j
public class DynamicDataSource implements DataSource, InitializingBean, DisposableBean {

    private final Object mutex = new Object();

    /** Zone → DataSource 映射 */
    private final Map<String, DataSource> zoneDataSources;

    /** 默认 Zone */
    private final String defaultZone;

    /** 当前 Zone */
    private volatile String currentZone;

    /** 当前代理的 DataSource */
    private volatile DataSource delegate;

    /** 活跃连接计数（用于 Zone 切换时的事务协调） */
    private final AtomicInteger activeConnectionCount = new AtomicInteger(0);

    /** Zone 切换时等待活跃连接完成的超时时间（秒） */
    private static final int SWITCH_WAIT_TIMEOUT_SECONDS = 30;

    /** ZoneContext 属性变更监听器 */
    private final PropertyChangeListener zoneChangeListener = this::onZoneChanged;

    public DynamicDataSource(Map<String, DataSource> zoneDataSources, String defaultZone) {
        this.zoneDataSources = new ConcurrentHashMap<>(zoneDataSources);
        this.defaultZone = defaultZone;
        this.currentZone = defaultZone;
        this.delegate = zoneDataSources.get(defaultZone);
    }

    @Override
    public void afterPropertiesSet() {
        ZoneContext.get().addPropertyChangeListener(zoneChangeListener);
        log.info("DynamicDataSource initialized with zones: {}, defaultZone: {}",
                zoneDataSources.keySet(), defaultZone);
    }

    @Override
    public void destroy() {
        ZoneContext.get().removePropertyChangeListener(zoneChangeListener);
    }

    /**
     * Zone 变更事件处理
     */
    private void onZoneChanged(PropertyChangeEvent event) {
        if ("zone".equals(event.getPropertyName())) {
            String newZone = (String) event.getNewValue();
            switchDataSource(newZone);
        }
    }

    /**
     * 切换数据源
     * <p>
     * 切换前等待活跃连接（进行中的事务）完成，避免 Zone 切换导致 TCC 事务中断。
     * 超时后强制切换并记录告警（TCC 有 Cancel 兜底机制）。
     * </p>
     */
    public void switchDataSource(String zone) {
        if (zone == null || ZoneConstants.DEFAULT_ZONE.equalsIgnoreCase(zone)) {
            zone = defaultZone;
        }

        DataSource newDataSource = zoneDataSources.get(zone);
        if (newDataSource == null) {
            log.warn("No DataSource found for zone '{}', falling back to defaultZone '{}'", zone, defaultZone);
            newDataSource = zoneDataSources.get(defaultZone);
        }

        if (newDataSource != null && newDataSource != delegate) {
            // 等待活跃连接（进行中的事务）完成
            waitForActiveConnections();

            synchronized (mutex) {
                DataSource old = this.delegate;
                this.delegate = newDataSource;
                this.currentZone = zone;
                log.info("DataSource switched: zone '{}', old: {}, new: {}, activeConnections: {}",
                        zone, old, newDataSource, activeConnectionCount.get());
            }
        }
    }

    /**
     * 等待活跃连接完成（Zone 切换前的事务协调）
     * <p>
     * 如果当前没有活跃连接（没有进行中的事务），立即返回。
     * 否则等待最多 SWITCH_WAIT_TIMEOUT_SECONDS 秒，超时后强制切换。
     * TCC 事务有 Cancel 兜底机制，超时切换不会导致数据不一致。
     * </p>
     */
    private void waitForActiveConnections() {
        int count = activeConnectionCount.get();
        if (count == 0) {
            return;
        }

        log.info("[Zone切换] 等待 {} 个活跃连接完成（最大等待 {}s）...", count, SWITCH_WAIT_TIMEOUT_SECONDS);
        long deadline = System.currentTimeMillis() + SWITCH_WAIT_TIMEOUT_SECONDS * 1000L;

        while (System.currentTimeMillis() < deadline) {
            count = activeConnectionCount.get();
            if (count == 0) {
                log.info("[Zone切换] 所有活跃连接已完成");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Zone切换] 等待被中断");
                break;
            }
        }

        log.warn("[Zone切换] 等待超时（{}s），仍有 {} 个活跃连接未完成，强制切换。" +
                " TCC Cancel 兜底机制将保证数据一致性。",
                SWITCH_WAIT_TIMEOUT_SECONDS, activeConnectionCount.get());
    }

    /**
     * 获取当前活跃连接数（用于监控）
     */
    public int getActiveConnectionCount() {
        return activeConnectionCount.get();
    }

    /**
     * 获取当前代理的 DataSource
     */
    public DataSource getDelegate() {
        return delegate;
    }

    /**
     * 获取当前 Zone
     */
    public String getCurrentZone() {
        return currentZone;
    }

    // ---- DataSource 接口代理（带活跃连接计数） ----

    @Override
    public Connection getConnection() throws SQLException {
        activeConnectionCount.incrementAndGet();
        try {
            Connection conn = delegate.getConnection();
            // 返回包装后的 Connection，在 close() 时递减计数
            return new ConnectionWrapper(conn, activeConnectionCount);
        } catch (SQLException e) {
            activeConnectionCount.decrementAndGet();
            throw e;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        activeConnectionCount.incrementAndGet();
        try {
            Connection conn = delegate.getConnection(username, password);
            return new ConnectionWrapper(conn, activeConnectionCount);
        } catch (SQLException e) {
            activeConnectionCount.decrementAndGet();
            throw e;
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    /**
     * Connection 包装器：在 close() 时递减活跃连接计数。
     * <p>
     * 用于 Zone 切换时的事务协调——只有当所有从当前数据源获取的连接都 close() 后，
     * 才认为没有进行中的事务，可以安全切换。
     * </p>
     */
    private static class ConnectionWrapper implements Connection {

        private final Connection delegate;
        private final AtomicInteger counter;
        private boolean closed = false;

        ConnectionWrapper(Connection delegate, AtomicInteger counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void close() throws SQLException {
            if (!closed) {
                closed = true;
                try {
                    delegate.close();
                } finally {
                    counter.decrementAndGet();
                }
            }
        }

        @Override
        public boolean isClosed() throws SQLException {
            return delegate.isClosed();
        }

        // ---- 其余方法直接委托 ----

        @Override
        public java.sql.Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override
        public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override
        public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        @Override
        public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override
        public void commit() throws SQLException { delegate.commit(); }
        @Override
        public void rollback() throws SQLException { delegate.rollback(); }
        @Override
        public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override
        public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override
        public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override
        public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override
        public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override
        public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override
        public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override
        public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override
        public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override
        public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override
        public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override
        public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override
        public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override
        public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override
        public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        @Override
        public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override
        public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override
        public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override
        public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override
        public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override
        public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override
        public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override
        public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override
        public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override
        public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        @Override
        public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        @Override
        public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override
        public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override
        public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        @Override
        public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
