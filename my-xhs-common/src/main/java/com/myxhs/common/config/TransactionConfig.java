package com.myxhs.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 事务管理器配置 — 全局默认超时 30 秒，验证已有事务隔离级别
 */
@Configuration
@EnableTransactionManagement
@ConditionalOnBean(DataSource.class)
@ConditionalOnClass(name = "org.springframework.jdbc.datasource.DataSourceTransactionManager")
public class TransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
        tm.setDefaultTimeout(30);                     // 默认超时 30 秒
        tm.setValidateExistingTransaction(true);       // 验证嵌套事务隔离级别
        return tm;
    }
}
