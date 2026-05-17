package com.myxhs.cart;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * 购物车服务启动类
 * <p>
 * 端口：19008
 * 功能：购物车管理（加购/删购/改数量/勾选/合并）
 * 依赖：Redis（权威数据源）、MySQL（异步持久化）、RocketMQ（异步落库）、Product服务（Feign）
 * </p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.myxhs.cart", "com.myxhs.common"})
@MapperScan("com.myxhs.cart.mapper")
@EnableFeignClients(basePackages = "com.myxhs.cart.feign")
public class CartApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartApplication.class, args);
    }
}