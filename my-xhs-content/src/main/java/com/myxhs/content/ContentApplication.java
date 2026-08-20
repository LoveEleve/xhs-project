package com.myxhs.content;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 内容服务启动类
 * <p>
 * 扫描 com.myxhs.common 包以加载公共组件（RedisOperator、CacheHelper、IdGeneratorUtil 等）。
 * </p>
 */
@SpringBootApplication(scanBasePackages = {"com.myxhs.content", "com.myxhs.common"})
@MapperScan("com.myxhs.content.mapper")
@EnableFeignClients(basePackages = "com.myxhs.content.feign")
@EnableScheduling
public class ContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}