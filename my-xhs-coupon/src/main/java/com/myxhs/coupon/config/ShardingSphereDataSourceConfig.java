package com.myxhs.coupon.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * 优惠券分片数据源（C-017 / coupon-sharding profile）
 * <p>
 * 默认 profile 不加载（走 common 的读写分离数据源）；
 * 显式 {@code --spring.profiles.active=coupon-sharding} 时启用：
 * t_user_coupon 按 user_id 路由到 2 库 × 16 表。
 * </p>
 */
@Slf4j
@Configuration
@Profile("coupon-sharding")
public class ShardingSphereDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource() throws SQLException, IOException {
        ClassPathResource resource = new ClassPathResource("sharding-config.yaml");
        String yaml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.info("[优惠券分片] 初始化 ShardingSphere 数据源（2 库 × 16 表）");
        return YamlShardingSphereDataSourceFactory.createDataSource(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage("com.myxhs.coupon.entity");
        return factoryBean.getObject();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
