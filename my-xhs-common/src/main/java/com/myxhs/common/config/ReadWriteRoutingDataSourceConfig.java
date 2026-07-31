package com.myxhs.common.config;

import com.myxhs.common.datasource.DataSourceType;
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

    @Bean
    public DataSource slaveDataSource(
            @Value("${spring.datasource.slave.jdbc-url}") String slaveUrl,
            @Value("${spring.datasource.slave.username}") String slaveUsername,
            @Value("${spring.datasource.slave.password}") String slavePassword,
            @Value("${spring.datasource.slave.driver-class-name}") String slaveDriver) {
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
        return ds;
    }

    @Bean
    @Primary
    public DataSource routingDataSource(DataSource masterDataSource, DataSource slaveDataSource) {
        ReadWriteRoutingDataSource routingDataSource = new ReadWriteRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceType.MASTER, masterDataSource);
        targetDataSources.put(DataSourceType.SLAVE, slaveDataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(masterDataSource);

        return routingDataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource routingDataSource) {
        return new JdbcTemplate(routingDataSource);
    }
}
