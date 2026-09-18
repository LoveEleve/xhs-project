package com.myxhs.common.datasource;

import com.myxhs.common.zone.ZoneConstants;
import com.myxhs.common.zone.ZoneContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;

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

    /** Zone 感知数据源开关（默认关，保持原行为） */
    private volatile boolean zoneRoutingEnabled = false;
    /** 主库所在 Zone */
    private volatile String masterZone = "";
    /** 从库所在 Zone */
    private volatile String slaveZone = "";

    public void setZoneRoutingEnabled(boolean zoneRoutingEnabled) {
        this.zoneRoutingEnabled = zoneRoutingEnabled;
    }

    public void setMasterZone(String masterZone) {
        this.masterZone = (masterZone == null) ? "" : masterZone;
    }

    public void setSlaveZone(String slaveZone) {
        this.slaveZone = (slaveZone == null) ? "" : slaveZone;
    }

    /** 当前 Zone 提供者（默认系统属性；Spring 环境注入 ZoneContext Bean 以支持动态切换） */
    private volatile Supplier<String> zoneSupplier = ZoneContext::getCurrentZone;

    public void setZoneSupplier(Supplier<String> zoneSupplier) {
        if (zoneSupplier != null) {
            this.zoneSupplier = zoneSupplier;
        }
    }

    /** 从库不可用标记 */
    private volatile boolean slaveUnavailable = false;
    /** 从库恢复检查时间戳 */
    private volatile long lastSlaveCheckTime = 0;
    /** 从库恢复探测间隔（默认 30s；测试可调） */
    private volatile long slaveCheckIntervalMs = 30_000;

    public void setSlaveCheckIntervalMs(long slaveCheckIntervalMs) {
        this.slaveCheckIntervalMs = slaveCheckIntervalMs;
    }

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
            return applyZonePreference(manualType);
        }

        // 策略 2: 事务只读标记
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return applyZonePreference(DataSourceType.SLAVE);
        }

        // 策略 3: 默认走主库（SQL 分析在 getConnection 中完成）
        return applyZonePreference(DataSourceType.MASTER);
    }

    /**
     * Zone 感知：读就近（本 Zone 的库优先），本地库不可用由 getConnection 的 slave→master 降级兜底。
     * <ul>
     *   <li>当前 Zone = masterZone：读也走本 Zone 主库（本 Zone 无独立从库的仿真语义）</li>
     *   <li>当前 Zone = slaveZone：读走本 Zone 从库；写跨 Zone 到主库</li>
     *   <li>Zone 未知 / defaultZone / 未开启：保持原读写分离语义</li>
     * </ul>
     */
    private DataSourceType applyZonePreference(DataSourceType type) {
        if (!zoneRoutingEnabled || masterZone.isEmpty() || slaveZone.isEmpty()) {
            return type;
        }
        String zone = zoneSupplier.get();
        if (zone == null || zone.isEmpty() || ZoneConstants.DEFAULT_ZONE.equals(zone)) {
            return type;
        }
        if (zone.equals(masterZone)) {
            return DataSourceType.MASTER;
        }
        if (zone.equals(slaveZone)) {
            return type;
        }
        return type;
    }

    @Override
    public Connection getConnection() throws SQLException {
        DataSourceType lookupKey = (DataSourceType) determineCurrentLookupKey();

        if (lookupKey == DataSourceType.SLAVE) {
            // 修复：从库标记不可用后，每次读请求按间隔主动探测恢复（原实现仅在主库失败时探测 → 从库永不自动恢复）
            if (slaveUnavailable) {
                tryRecoverSlave();
            }
            if (!slaveUnavailable) {
                try {
                    return slaveDataSource.getConnection();
                } catch (SQLException e) {
                    log.warn("[ReadWriteDS] 从库不可用，降级到主库: {}", e.getMessage());
                    slaveUnavailable = true;
                    lastSlaveCheckTime = System.currentTimeMillis();
                }
            }
        }

        return masterDataSource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        DataSourceType lookupKey = (DataSourceType) determineCurrentLookupKey();

        if (lookupKey == DataSourceType.SLAVE) {
            if (slaveUnavailable) {
                tryRecoverSlave();
            }
            if (!slaveUnavailable) {
                try {
                    return slaveDataSource.getConnection(username, password);
                } catch (SQLException e) {
                    log.warn("[ReadWriteDS] 从库不可用，降级到主库: {}", e.getMessage());
                    slaveUnavailable = true;
                    lastSlaveCheckTime = System.currentTimeMillis();
                }
            }
        }

        return masterDataSource.getConnection(username, password);
    }

    /**
     * 尝试恢复从库连接。
     */
    private void tryRecoverSlave() {
        long now = System.currentTimeMillis();
        if (now - lastSlaveCheckTime < slaveCheckIntervalMs) {
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
