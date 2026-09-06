package com.myxhs.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 事务管理器配置 — 全局默认超时 30 秒，验证已有事务隔离级别
 * <p>
 * 注意：不能在类级用 {@code @ConditionalOnBean(DataSource.class)}——该条件在
 * {@code @Configuration} 类上的评估早于 DataSource bean 注册（时序不可靠），
 * 实测 content 服务因此未加载本配置类、无 transactionManager，
 * 所有 {@code @Transactional} 静默退化为无事务（Transaction synchronization not active）。
 * 改为方法参数注入 DataSource（有 DataSource 才可构造，天然满足依赖）。
 * </p>
 */
@Configuration
@EnableTransactionManagement
@ConditionalOnClass(name = "org.springframework.jdbc.datasource.DataSourceTransactionManager")
public class TransactionConfig {

    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
        tm.setDefaultTimeout(30);                     // 默认超时 30 秒
        tm.setValidateExistingTransaction(true);       // 验证嵌套事务隔离级别
        return tm;
    }
}
