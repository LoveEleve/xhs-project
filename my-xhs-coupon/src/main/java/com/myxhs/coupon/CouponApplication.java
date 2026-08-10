package com.myxhs.coupon;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 优惠券服务启动类
 * <p>
 * 端口：19010
 * 功能：优惠券模板管理、Lua 原子领券、责任链用券校验、退券
 * 依赖：Redis（Lua 原子领券 + 库存 + 防重复）、MySQL（持久化）、RocketMQ（异步写 DB）
 * </p>
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.myxhs.coupon", "com.myxhs.common"})
@MapperScan("com.myxhs.coupon.mapper")
public class CouponApplication {
    public static void main(String[] args) {
        SpringApplication.run(CouponApplication.class, args);
    }
}