package com.myxhs.common.config;

import com.myxhs.common.datasource.DataSourceType;
import com.myxhs.common.zone.ZoneContext;
import com.myxhs.common.datasource.ReadWriteRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 读写分离数据源配置。
 * 仅在 {@code spring.datasource.readwrite.enabled=true} 时生效。
 */
@Configuration
@PropertySource(value = "classpath:application-datasource.properties", ignoreResourceNotFound = true)
@ConditionalOnProperty(prefix = "spring.datasource.readwrite", name = "enabled", havingValue = "true")
public class ReadWriteRoutingDataSourceConfig {

    @Bean
    public DataSource masterDataSource(
            @Value("${spring.datasource.master.jdbc-url}") String masterUrl,
            @Value("${spring.datasource.master.username}") String masterUsername,
            @Value("${spring.datasource.master.password}") String masterPassword,
            @Value("${spring.datasource.master.driver-class-name}") String masterDriver) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(masterUrl);
        ds.setUsername(masterUsername);
        ds.setPassword(masterPassword);
        ds.setDriverClassName(masterDriver);
        ds.setMinimumIdle(5);
        ds.setMaximumPoolSize(20);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(1800000);
        ds.setConnectionTimeout(10000);
        return ds;
    }

    /**
     * 从库数据源（不暴露为独立 Bean）。
     * <p>
     * 设计说明：Spring Boot 的 db 健康检查会评估容器内所有 DataSource Bean，
     * 从库宕机时会导致 /actuator/health=503 → 发布健康校验失败回滚（2026-09-18 实测发现）。
     * 从库是可降级组件（路由层已有"从库不可用 → 主库"兜底），因此它不应参与整体健康判定。
     * </p>
     */
    private DataSource createSlaveDataSource(String slaveUrl, String slaveUsername,
                                             String slavePassword, String slaveDriver) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(slaveUrl);
        ds.setUsername(slaveUsername);
        ds.setPassword(slavePassword);
        ds.setDriverClassName(slaveDriver);
        ds.setMinimumIdle(5);
        ds.setMaximumPoolSize(10);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(1800000);
        ds.setConnectionTimeout(10000);
        ds.setReadOnly(true);
        // 从库故障不应阻止服务启动：路由层已有"从库不可用 → 主库降级"兜底
        ds.setInitializationFailTimeout(-1);
        return ds;
    }

    @Bean
    @Primary
    public DataSource routingDataSource(DataSource masterDataSource,
                                        @Value("${spring.datasource.slave.jdbc-url}") String slaveUrl,
                                        @Value("${spring.datasource.slave.username}") String slaveUsername,
                                        @Value("${spring.datasource.slave.password}") String slavePassword,
                                        @Value("${spring.datasource.slave.driver-class-name}") String slaveDriver,
                                        ZoneContext zoneContext,
                                        @Value("${myxhs.availability.zone.datasource.enabled:false}") boolean zoneRoutingEnabled,
                                        @Value("${myxhs.availability.zone.datasource.master-zone:}") String masterZone,
                                        @Value("${myxhs.availability.zone.datasource.slave-zone:}") String slaveZone) {
        ReadWriteRoutingDataSource routingDataSource = new ReadWriteRoutingDataSource();
        routingDataSource.setZoneRoutingEnabled(zoneRoutingEnabled);
        routingDataSource.setZoneSupplier(zoneContext::getZone);
        routingDataSource.setMasterZone(masterZone);
        routingDataSource.setSlaveZone(slaveZone);

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceType.MASTER, masterDataSource);
        targetDataSources.put(DataSourceType.SLAVE,
                createSlaveDataSource(slaveUrl, slaveUsername, slavePassword, slaveDriver));

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(masterDataSource);

        return routingDataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource routingDataSource) {
        return new JdbcTemplate(routingDataSource);
    }
}
