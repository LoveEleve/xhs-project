package com.myxhs.im;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * 即时通讯服务启动类
 * <p>
 * 基于 Spring MVC WebSocket（Tomcat NIO），而非 WebFlux。
 * 原因：MyBatis Plus 是阻塞式的，混用 Reactive 和阻塞 IO 会导致复杂度爆炸。
 * Tomcat NIO 模式下 WebSocket 连接不占用线程（只在有消息时分配线程处理），
 * 单机 10 万连接完全可行。
 * </p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.myxhs.im.mapper")
@ComponentScan(basePackages = {"com.myxhs.im", "com.myxhs.common"})
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }
}