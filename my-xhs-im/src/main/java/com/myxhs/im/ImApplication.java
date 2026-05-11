package com.myxhs.im;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 即时通讯服务启动类 - WebSocket私信、消息存储、会话管理
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class ImApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }
}