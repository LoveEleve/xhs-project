package com.myxhs.counter;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 计数服务启动类
 * <p>
 * 统一管理所有计数：点赞数、收藏数、评论数、粉丝数、关注数、浏览数等。
 * 核心技术：Redis INCR/DECR + Buffer-Trigger 攒批刷盘 + Redis→MySQL 两级缓存 + 对账修复。
 * </p>
 */
@SpringBootApplication(scanBasePackages = {"com.myxhs.counter", "com.myxhs.common"})
@MapperScan("com.myxhs.counter.mapper")
@EnableScheduling
public class CounterApplication {
    public static void main(String[] args) {
        SpringApplication.run(CounterApplication.class, args);
    }
}