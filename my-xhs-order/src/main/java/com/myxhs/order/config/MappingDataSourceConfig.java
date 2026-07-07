package com.myxhs.order.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 映射表独立数据源配置
 * <p>
 * 订单号映射表（t_order_no_mapping）存储在公共库 my_xhs_order 中，
 * 不走 ShardingSphere 分片路由，使用独立的 HikariCP 数据源 + JdbcTemplate。
 * </p>
 * <p>
 * 为什么用 JdbcTemplate 而不是 MyBatis Mapper？
 * 1. 映射表只有 3 个简单操作（insert/selectByOrderNo/selectByOrderId）
 * 2. 避免 @MapperScan 递归扫描子包导致 Mapper 被主 SqlSessionFactory 注册
 * 3. JdbcTemplate 轻量、无额外配置，适合简单的辅助表操作
 * </p>
 */
@Configuration
public class MappingDataSourceConfig {

    @Value("${order.mapping.datasource.url}")
    private String url;

    @Value("${order.mapping.datasource.username}")
    private String username;

    @Value("${order.mapping.datasource.password}")
    private String password;

    @Bean("mappingDataSource")
    public DataSource mappingDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMinimumIdle(2);
        ds.setMaximumPoolSize(5);
        ds.setIdleTimeout(30000);
        ds.setMaxLifetime(1800000);
        ds.setConnectionTimeout(10000);
        ds.setKeepaliveTime(30000);
        ds.setConnectionTestQuery("SELECT 1");
        ds.setValidationTimeout(3000);
        ds.setPoolName("HikariPool-Mapping");
        return ds;
    }

    @Bean("mappingJdbcTemplate")
    public JdbcTemplate mappingJdbcTemplate(@Qualifier("mappingDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
