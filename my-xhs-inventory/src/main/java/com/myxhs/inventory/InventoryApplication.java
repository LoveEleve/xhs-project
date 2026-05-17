package com.myxhs.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 库存服务启动类
 * <p>
 * 端口：19009
 * 功能：库存管理（分桶预扣减/确认/释放/对账修复）
 * 依赖：Redis（分桶库存 + Lua 原子扣减）、MySQL（持久化）、RocketMQ（异步落库）
 * </p>
 * <p>
 * 三级扣减保证：
 * L1: Redis 分桶预扣（毫秒级，用户立即得到结果）
 * L2: MQ 异步扣 MySQL（保证持久化）
 * L3: 定时对账修复（Redis ↔ MySQL 最终一致）
 * </p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.myxhs.inventory", "com.myxhs.common"})
@MapperScan("com.myxhs.inventory.mapper")
public class InventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}