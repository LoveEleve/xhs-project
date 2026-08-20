package com.myxhs.analytics;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.myxhs.analytics.feign")
@ComponentScan(basePackages = {"com.myxhs.analytics", "com.myxhs.common"})
@MapperScan("com.myxhs.analytics.mapper")
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}