package com.myxhs.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 商品服务启动类
 * <p>
 * Phase-2 电商交易链路的起点。
 * 提供 SPU/SKU 管理、三级分类树、多级缓存（Caffeine → Redis → MySQL）。
 * </p>
 */
@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.myxhs.product", "com.myxhs.common"})
public class ProductApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}