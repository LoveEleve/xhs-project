package com.myxhs.payment.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 支付数据源配置（独立于 ShardingSphere）
 * <p>
 * 支付表 t_payment 和 t_refund 存储在独立库 my_xhs_payment 中，
 * 不走 ShardingSphere 分片路由，保证支付数据路由可控。
 * </p>
 * <p>
 * 为什么要独立数据源？
 * 1. ShardingSphere 会拦截 SQL 路由到分片库，支付表需要精确路由到 my_xhs_payment 库
 * 2. 支付记录需要支持非分片键查询（如按订单号查询支付状态）
 * 3. 退款流程需要对支付记录做乐观锁状态更新，必须确保原子路由
 * </p>
 */
@Configuration
public class PaymentDataSourceConfig {

    /**
     * 支付数据源（直连 my_xhs_payment 库）
     * <p>
     * 配置来自 application.yml 中的 pay.datasource 配置项。
     * </p>
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.pay-datasource")
    public DataSource paymentDataSource() {
        return new HikariDataSource();
    }

    /**
     * 支付专用 JdbcTemplate
     * <p>
     * 所有支付相关的数据库操作都通过此 JdbcTemplate 执行，
     * 确保不被 ShardingSphere 拦截。
     * </p>
     */
    /**
     * 支付库专用事务管理器
     * <p>
     * 支付域写 t_payment/t_refund/t_settlement_* 走 paymentJdbcTemplate（独立 DataSource），
     * 而全局 @Primary 事务管理器绑定的是 routingDataSource（订单/分片库）——
     * 不指定 transactionManager 时 @Transactional 对该库完全不生效（跨 DataSource 无事务同步）。
     * </p>
     */
    @Bean
    public org.springframework.jdbc.datasource.DataSourceTransactionManager paymentTransactionManager(
            @Qualifier("paymentDataSource") DataSource dataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }

    @Bean
    public JdbcTemplate paymentJdbcTemplate(@Qualifier("paymentDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
