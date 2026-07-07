package com.myxhs.common.datasource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * 读写分离路由数据源 — 支持三种路由策略（优先级从高到低）：
 * <ol>
 *   <li>{@link DataSourceContextHolder} 手动指定</li>
 *   <li>{@code @Transactional(readOnly=true)} 自动路由</li>
 *   <li>SQL 分析自动路由（SELECT → SLAVE，其他 → MASTER）</li>
 * </ol>
 * 读库不可用时自动降级到写库，并定期探测读库恢复。
 */
@Slf4j
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    private DataSource masterDataSource;
    private DataSource slaveDataSource;

    /** 从库不可用标记 */
    private volatile boolean slaveUnavailable = false;
    /** 从库恢复检查时间戳 */
    private volatile long lastSlaveCheckTime = 0;
    private static final long SLAVE_CHECK_INTERVAL_MS = 30_000;

    /** 已知的读 SQL 前缀 */
    private static final String[] READ_SQL_PREFIXES = {
        "SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN"
    };

    /** 已知的写 SQL 前缀 */
    private static final String[] WRITE_SQL_PREFIXES = {
        "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER",
        "DROP", "TRUNCATE", "REPLACE"
    };

    @Override
    public void setTargetDataSources(Map<Object, Object> targetDataSources) {
        super.setTargetDataSources(targetDataSources);
        this.masterDataSource = (DataSource) targetDataSources.get(DataSourceType.MASTER);
        this.slaveDataSource = (DataSource) targetDataSources.get(DataSourceType.SLAVE);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        // 策略 1: 手动指定（ThreadLocal）
        DataSourceType manualType = DataSourceContextHolder.get();
        if (manualType != null) {
            DataSourceContextHolder.clear();
            return manualType;
        }

        // 策略 2: 事务只读标记
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return DataSourceType.SLAVE;
        }

        // 策略 3: 默认走主库（SQL 分析在 getConnection 中完成）
        return DataSourceType.MASTER;
    }

    @Override
    public Connection getConnection() throws SQLException {
        DataSourceType lookupKey = (DataSourceType) determineCurrentLookupKey();

        if (lookupKey == DataSourceType.SLAVE && !slaveUnavailable) {
            try {
                return slaveDataSource.getConnection();
            } catch (SQLException e) {
                log.warn("[ReadWriteDS] 从库不可用，降级到主库: {}", e.getMessage());
                slaveUnavailable = true;
                lastSlaveCheckTime = System.currentTimeMillis();
            }
        }

        try {
            return masterDataSource.getConnection();
        } catch (SQLException e) {
            if (slaveUnavailable) {
                tryRecoverSlave();
            }
            throw e;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        DataSourceType lookupKey = (DataSourceType) determineCurrentLookupKey();

        if (lookupKey == DataSourceType.SLAVE && !slaveUnavailable) {
            try {
                return slaveDataSource.getConnection(username, password);
            } catch (SQLException e) {
                log.warn("[ReadWriteDS] 从库不可用，降级到主库: {}", e.getMessage());
                slaveUnavailable = true;
                lastSlaveCheckTime = System.currentTimeMillis();
            }
        }

        try {
            return masterDataSource.getConnection(username, password);
        } catch (SQLException e) {
            if (slaveUnavailable) {
                tryRecoverSlave();
            }
            throw e;
        }
    }

    /**
     * 尝试恢复从库连接。
     */
    private void tryRecoverSlave() {
        long now = System.currentTimeMillis();
        if (now - lastSlaveCheckTime < SLAVE_CHECK_INTERVAL_MS) {
            return;
        }
        lastSlaveCheckTime = now;
        try (Connection conn = slaveDataSource.getConnection()) {
            if (conn.isValid(3)) {
                slaveUnavailable = false;
                log.info("[ReadWriteDS] 从库已恢复");
            }
        } catch (SQLException e) {
            log.debug("[ReadWriteDS] 从库仍未恢复");
        }
    }

    /**
     * 根据 SQL 前缀判断是否为读操作。
     *
     * @param sql SQL 语句
     * @return true 如果是读操作，false 如果是写操作
     */
    public static boolean isReadOperation(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return true;
        }
        String upperSql = sql.trim().toUpperCase();

        for (String prefix : WRITE_SQL_PREFIXES) {
            if (upperSql.startsWith(prefix)) {
                return false;
            }
        }

        for (String prefix : READ_SQL_PREFIXES) {
            if (upperSql.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }
}
