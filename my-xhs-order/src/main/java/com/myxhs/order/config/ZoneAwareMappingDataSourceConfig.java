package com.myxhs.order.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 映射表数据源 · Zone 动态路由版（与 ShardingSphere 同应用并存）
 *
 * <p>背景：订单主表走 ShardingSphere（4 库 × 4 表）；订单号映射表走独立数据源绕过分片。
 * 本配置为映射表数据源增加"Zone 动态路由"能力——master（3306）/slave（3307）双目标，
 * 运行时可切换，用于演示「ShardingSphere 分片 + 动态 JDBC 数据源」在同一应用内并存/隔离。</p>
 *
 * <p>开关：{@code order.mapping.zone-routing.enabled=true} 时启用（原 MappingDataSourceConfig 自动退让，
 * 避免同名 Bean 冲突）；默认关闭，行为与之前完全一致。</p>
 *
 * <p>注意：slave 为只读副本，切换后仅适合读路径验证；写路径请切回 master（验证脚本会切回）。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "order.mapping.zone-routing.enabled", havingValue = "true")
public class ZoneAwareMappingDataSourceConfig {

    public static final String TARGET_MASTER = "master";
    public static final String TARGET_SLAVE = "slave";

    private static final AtomicReference<String> TARGET = new AtomicReference<>(TARGET_MASTER);

    @Value("${order.mapping.datasource.url}")
    private String url;

    @Value("${order.mapping.datasource.username}")
    private String username;

    @Value("${order.mapping.datasource.password}")
    private String password;

    @Value("${order.mapping.datasource.slave-url:}")
    private String slaveUrlRaw;

    private String slaveUrl() {
        if (slaveUrlRaw != null && !slaveUrlRaw.isBlank()) {
            return slaveUrlRaw;
        }
        return url.replace(":3306/", ":3307/");
    }

    private HikariDataSource build(String jdbcUrl, String poolName, int maxPool) {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMinimumIdle(2);
        ds.setMaximumPoolSize(maxPool);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(1800000);
        ds.setConnectionTimeout(10000);
        ds.setKeepaliveTime(30000);
        ds.setConnectionTestQuery("SELECT 1");
        ds.setValidationTimeout(3000);
        ds.setPoolName(poolName);
        return ds;
    }

    @Bean("mappingMasterDataSource")
    public DataSource mappingMasterDataSource() {
        return build(url, "HikariPool-Mapping-Master", 5);
    }

    @Bean("mappingSlaveDataSource")
    public DataSource mappingSlaveDataSource() {
        return build(slaveUrl(), "HikariPool-Mapping-Slave", 3);
    }

    @Bean("mappingDataSource")
    public DataSource mappingDataSource(@Qualifier("mappingMasterDataSource") DataSource master,
                                        @Qualifier("mappingSlaveDataSource") DataSource slave) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TARGET.get();
            }
        };
        routing.setTargetDataSources(Map.of(TARGET_MASTER, master, TARGET_SLAVE, slave));
        routing.setDefaultTargetDataSource(master);
        routing.afterPropertiesSet();
        log.info("[ZoneDS] 映射表动态数据源启用: master={}, slave={}, 当前目标={}", url, slaveUrl(), TARGET.get());
        return routing;
    }

    @Bean("mappingJdbcTemplate")
    public JdbcTemplate mappingJdbcTemplate(@Qualifier("mappingDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    public static String currentTarget() {
        return TARGET.get();
    }

    public static void switchTarget(String target) {
        if (!TARGET_MASTER.equals(target) && !TARGET_SLAVE.equals(target)) {
            throw new IllegalArgumentException("target 只能是 master/slave");
        }
        TARGET.set(target);
        log.info("[ZoneDS] 映射表数据源切换: target={}", target);
    }
}
