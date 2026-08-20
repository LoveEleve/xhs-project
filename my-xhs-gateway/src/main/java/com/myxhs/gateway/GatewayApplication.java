package com.myxhs.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

/**
 * Gateway 启动类
 * <p>
 * 排除 DataSource 自动配置（Gateway 不需要数据库连接）。
 * Gateway 是基于 WebFlux 的响应式网关，不使用传统 WebMVC 和 JDBC。
 * 注：gateway 不依赖 common 模块（独立实现）——application 公共标签由 GatewayMetricsConfig 注入。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}