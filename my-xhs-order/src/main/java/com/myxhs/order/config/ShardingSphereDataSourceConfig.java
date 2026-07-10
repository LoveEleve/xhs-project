package com.myxhs.order.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * ShardingSphere 数据源配置
 * <p>
 * 手动创建 ShardingSphere 数据源，替代 Spring Boot 的自动配置。
 * 原因：Spring Boot 3.2.5 的 DataSourceAutoConfiguration 无法正确识别
 * jdbc:shardingsphere: 协议，手动通过 YamlShardingSphereDataSourceFactory 创建更可靠。
 * </p>
 * <p>
 * Snowflake worker-id 动态计算策略：
 * 1. 优先使用环境变量 WORKER_ID（K8s/Docker 部署时注入）
 * 2. 兜底：基于本机 IP 后两段计算（ip[2] * 256 + ip[3]) % 1024
 * 保证同一子网内不同实例的 worker-id 不冲突
 * </p>
 */
@Slf4j
@Configuration
public class ShardingSphereDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource() throws SQLException, IOException {
        ClassPathResource resource = new ClassPathResource("sharding-config.yaml");
        String yamlContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // 动态计算 worker-id 并替换 YAML 中的占位值
        int workerId = resolveWorkerId();
        yamlContent = yamlContent.replace("worker-id: 1", "worker-id: " + workerId);
        log.info("[ShardingSphere] Snowflake worker-id={}", workerId);

        return YamlShardingSphereDataSourceFactory.createDataSource(yamlContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * MyBatis-Plus SqlSessionFactory
     * <p>
     * 由于排除了 MybatisPlusAutoConfiguration（它无法正确处理 ShardingSphere DataSource），
     * 此处手动创建 SqlSessionFactory。ShardingSphere DataSource 作为唯一数据源注入。
     * </p>
     */
    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }

    /**
     * 默认 JdbcTemplate（基于 ShardingSphere 数据源）
     * <p>
     * 供 SegmentIdGenerator 等通用组件注入使用。
     * 注意：此 JdbcTemplate 的 SQL 会经过 ShardingSphere 路由，
     * 对于未配置分片规则的表（如 t_id_segment），ShardingSphere 会透传到默认数据源。
     * </p>
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 计算 Snowflake worker-id
     * <p>
     * 优先级：
     * 1. 环境变量 WORKER_ID（生产环境通过 K8s Downward API 或 Docker 注入）
     * 2. 系统属性 -Dworker.id=N
     * 3. 兜底：基于本机 IP 后两段计算 (ip[2] * 256 + ip[3]) % 1024
     * </p>
     */
    private int resolveWorkerId() {
        // 1. 环境变量
        String envWorkerId = System.getenv("WORKER_ID");
        if (envWorkerId != null && !envWorkerId.isEmpty()) {
            return Integer.parseInt(envWorkerId);
        }

        // 2. 系统属性
        String propWorkerId = System.getProperty("worker.id");
        if (propWorkerId != null && !propWorkerId.isEmpty()) {
            return Integer.parseInt(propWorkerId);
        }

        // 3. 基于 IP 计算
        try {
            InetAddress addr = InetAddress.getLocalHost();
            byte[] ip = addr.getAddress();
            // 取 IP 后两段，范围 0~65535，取模 1024（Snowflake worker-id 范围 0~1023）
            return ((ip[2] & 0xFF) * 256 + (ip[3] & 0xFF)) % 1024;
        } catch (Exception e) {
            log.warn("[ShardingSphere] 无法获取本机IP，使用默认 worker-id=1", e);
            return 1;
        }
    }
}
