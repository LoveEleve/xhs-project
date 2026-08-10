package com.myxhs.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 商品服务启动类
 * <p>
 * Phase-2 电商交易链路的起点。
 * 提供 SPU/SKU 管理、三级分类树、Redis 逻辑过期缓存 + MySQL 兜底。
 * </p>
 */
@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.myxhs.product", "com.myxhs.common"})
public class ProductApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}