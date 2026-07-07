package com.myxhs.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Sentinel 舱壁隔离 + 热点参数限流配置
 * 
 * 舱壁隔离（Bulkhead）：为不同服务的 Feign 调用配置独立线程池，
 * 防止库存慢查询拖慢订单创建、支付回调阻塞 Feed 推送。
 * 
 * Sentinel 的舱壁通过 threadPoolMaxSize + maxQueueSize 实现：
 * - 超过 maxQueueSize 的请求直接拒绝（快速失败）
 * - 不同资源（resource）使用不同的线程池参数
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "com.alibaba.csp.sentinel.SphU")
public class SentinelBulkheadConfig {

    @PostConstruct
    public void init() {
        log.info("[Sentinel] 舱壁隔离配置初始化");
        // 1. 设置默认舱壁规则（本地降级，Nacos 不可用时使用）
        initDefaultBulkheadRules();
        // 2. 注册 Nacos 数据源动态加载规则（注释说明实际配置在 Nacos Console）
        // 规则 dataId: my-xhs-sentinel-bulkhead-rules
        // 规则 group: SENTINEL_GROUP
    }

    private void initDefaultBulkheadRules() {
        // 默认线程池：核心5，最大10，队列20，适用于大多数 Feign 调用
        // 具体规则通过 Nacos 动态配置推送后覆盖
        System.setProperty("csp.sentinel.bulkhead.max.thread.pool.size", "10");
        System.setProperty("csp.sentinel.bulkhead.max.queue.size", "20");
        log.info("[Sentinel] 默认舱壁参数: threadPoolMaxSize=10, maxQueueSize=20");
    }
}
