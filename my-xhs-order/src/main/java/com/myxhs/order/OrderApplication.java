package com.myxhs.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * 订单服务启动类
 * <p>
 * 端口：19011
 * 功能：订单创建（事务消息）、状态机管理、超时关单、Mock 支付
 * 依赖：Redis（幂等/分布式锁/缓存）、MySQL（订单/本地消息表）、RocketMQ（事务消息/延时消息）
 * </p>
 * <p>
 * 排除 DataSourceAutoConfiguration：
 * ShardingSphere 数据源由 ShardingSphereDataSourceConfig 手动创建，
 * 不使用 Spring Boot 的自动配置（避免 HikariCP 与 ShardingSphere Driver 冲突）。
 * </p>
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@ComponentScan(basePackages = {"com.myxhs.order", "com.myxhs.common"})
@MapperScan("com.myxhs.order.mapper")
@EnableFeignClients(basePackages = "com.myxhs.order.feign")
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}