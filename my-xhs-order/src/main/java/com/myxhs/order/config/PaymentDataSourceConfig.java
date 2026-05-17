package com.myxhs.order.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 支付表独立数据源配置
 * <p>
 * 支付表（t_payment）存储在独立库 my_xhs_payment 中，
 * 不走 ShardingSphere 分片路由，使用独立的 HikariCP 数据源 + JdbcTemplate。
 * </p>
 * <p>
 * 为什么支付表不走分片？
 * 1. 支付表属于支付域，未来会拆分为独立的支付服务
 * 2. 支付表的查询维度是 order_id / payment_no，不适合用 user_id 分片
 * 3. 支付表数据量远小于订单表，无需分片
 * </p>
 */
@Configuration
public class PaymentDataSourceConfig {

    @Value("${pay.datasource.url}")
    private String url;

    @Value("${pay.datasource.username}")
    private String username;

    @Value("${pay.datasource.password}")
    private String password;

    @Bean("paymentDataSource")
    public DataSource paymentDataSource() {
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
        ds.setPoolName("HikariPool-Payment");
        return ds;
    }

    @Bean("paymentJdbcTemplate")
    public JdbcTemplate paymentJdbcTemplate(@Qualifier("paymentDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
