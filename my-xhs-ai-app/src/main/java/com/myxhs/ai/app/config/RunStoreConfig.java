package com.myxhs.ai.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.store.JdbcRunStore;
import com.myxhs.ai.app.service.store.RunStore;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * M5 Run Store 装配：AI 自有库（my_xhs_ai）第二数据源，与业务只读数据源物理隔离。
 */
@Configuration
public class RunStoreConfig {

    @Bean
    public DataSource aiDataSource(
            @Value("${spring.ai-datasource.url}") String url,
            @Value("${spring.ai-datasource.username}") String username,
            @Value("${spring.ai-datasource.password}") String password) {
        if (url != null && url.startsWith("jdbc:mysql") && (password == null || password.isBlank())) {
            throw new IllegalStateException("spring.ai-datasource.password 未配置（AI 自有库写账号），密钥不得落库");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(3);
        return ds;
    }

    @Bean
    public JdbcTemplate aiJdbcTemplate(DataSource aiDataSource) {
        return new JdbcTemplate(aiDataSource);
    }

    @Bean
    public RunStore runStore(JdbcTemplate aiJdbcTemplate, ObjectMapper om) {
        return new JdbcRunStore(aiJdbcTemplate, om);
    }
}
