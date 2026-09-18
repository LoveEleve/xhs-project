package com.myxhs.content.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * 多 ORM 并存演示：Spring Data JPA（与既有 MyBatis / MyBatis-Plus 同应用）
 * <p>开关 {@code content.jpa.enabled=true}；复用主（@Primary）数据源，DDL 关闭（只读使用）。
 * 与 MyBatis 各自独立事务管理器（jpaTransactionManager），互不干扰。</p>
 */
@Configuration
@ConditionalOnProperty(name = "content.jpa.enabled", havingValue = "true")
@EnableJpaRepositories(basePackages = "com.myxhs.content.jpa", transactionManagerRef = "jpaTransactionManager")
@EntityScan(basePackages = "com.myxhs.content.jpa")
public class ContentJpaConfig {

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("com.myxhs.content.jpa");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties props = new Properties();
        props.setProperty("hibernate.hbm2ddl.auto", "none");
        props.setProperty("hibernate.show_sql", "false");
        props.setProperty("hibernate.format_sql", "false");
        emf.setJpaProperties(props);
        return emf;
    }

    @Bean
    public JpaTransactionManager jpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
