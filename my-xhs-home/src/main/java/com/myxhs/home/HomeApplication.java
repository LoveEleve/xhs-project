package com.myxhs.home;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 首页聚合服务启动类 - BFF聚合层
 * 提供Feed流、笔记详情、商品详情、用户主页等聚合接口
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class HomeApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomeApplication.class, args);
    }
}