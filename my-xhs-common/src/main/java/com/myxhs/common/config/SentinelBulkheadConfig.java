package com.myxhs.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Sentinel 限流/降级规则加载声明
 * <p>
 * 【2026-09-23 修正】原实现用 {@code System.setProperty("csp.sentinel.bulkhead.*")} 宣称配置"舱壁隔离"，
 * 但这些键并非 Sentinel 的配置项（Sentinel 没有开箱的 Feign 线程池舱壁），属无效配置——
 * 实际生效的隔离手段是：① Sentinel 流控/降级规则（Nacos 数据源推送，见 GatewayConfig/SentinelDataSourceHandler）；
 * ② Feign 全局关闭重试 + 连接/读超时（FeignSafeConfig + 各服务 yml）；③ 自研最小连接 LB。
 * 因此本类只保留启动日志，不再设置任何伪配置；"Feign 线程池舱壁"列为本项目设计项。
 * </p>
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "com.alibaba.csp.sentinel.SphU")
public class SentinelBulkheadConfig {

    @PostConstruct
    public void init() {
        // 规则由 Nacos 数据源推送（spring.cloud.sentinel.datasource.*），此处仅声明启动状态
        log.info("[Sentinel] 限流/降级规则由 Nacos 数据源加载（Feign 线程池舱壁为设计项，见类注释）");
    }
}
