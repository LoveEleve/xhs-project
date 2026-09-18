package com.myxhs.common.zone.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态 Zone 数据源接线（D3-9 试点）。
 * <p>
 * 将主库与只读副本按 Zone 组装为 {@link DynamicDataSource}：
 * <ul>
 *   <li>zone-a → master（本地主库）</li>
 *   <li>zone-b → slave（本 zone 只读副本，独立 Hikari，仿真 zone-b 的库）</li>
 * </ul>
 * 当 {@code ZoneContext} 的 zone 变更时，DynamicDataSource 热切换代理目标
 * （切换前等待活跃连接完成，TCC 事务安全）。
 * </p>
 * <p>默认关闭：{@code myxhs.availability.zone.dynamic-datasource.enabled=true} 开启；
 * 开启时接管 {@code routingDataSource}（后者自动退让，避免双 @Primary）。</p>
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.dynamic-datasource", name = "enabled", havingValue = "true")
public class DynamicZoneDataSourceConfig {

    @Bean
    @Primary
    public DynamicDataSource dynamicZoneDataSource(
            @Qualifier("masterDataSource") DataSource masterDataSource,
            @Value("${spring.datasource.slave.jdbc-url}") String slaveUrl,
            @Value("${spring.datasource.slave.username}") String slaveUsername,
            @Value("${spring.datasource.slave.password}") String slavePassword,
            @Value("${spring.datasource.slave.driver-class-name}") String slaveDriver,
            @Value("${myxhs.availability.zone.dynamic-datasource.zone-a:zone-a}") String zoneA,
            @Value("${myxhs.availability.zone.dynamic-datasource.zone-b:zone-b}") String zoneB) {
        Map<String, DataSource> zoneDataSources = new LinkedHashMap<>();
        zoneDataSources.put(zoneA, masterDataSource);
        zoneDataSources.put(zoneB, createSlaveDataSource(slaveUrl, slaveUsername, slavePassword, slaveDriver));
        return new DynamicDataSource(zoneDataSources, zoneA);
    }

    /**
     * zone-b 只读副本数据源（独立 Hikari；从库故障不阻塞启动）
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
        ds.setInitializationFailTimeout(-1);
        return ds;
    }
}
