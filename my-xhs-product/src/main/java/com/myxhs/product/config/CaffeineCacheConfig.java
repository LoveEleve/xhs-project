package com.myxhs.product.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myxhs.product.dto.response.CategoryTreeVO;
import com.myxhs.product.dto.response.SpuDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置（多级缓存 L1 层）
 * <p>
 * 【设计决策】
 * 商品详情页 QPS 万级，全走 Redis 会打满带宽。Caffeine 作为 L1 本地缓存，
 * 承担 80% 热点流量（微秒级响应），Redis 作为 L2 承担 19%，MySQL 兜底 1%。
 * <p>
 * 【一致性策略】
 * 多实例 Caffeine 不共享 → 商品变更时通过 MQ 广播清除所有实例的本地缓存。
 * TTL 5 分钟兜底：即使 MQ 丢消息，最多 5 分钟后自动过期。
 * </p>
 */
@Slf4j
@Configuration
public class CaffeineCacheConfig {

    /**
     * SPU 详情本地缓存
     * <p>
     * maximumSize=5000：最多缓存 5000 个 SPU（按 LRU 淘汰）
     * expireAfterWrite=5min：写入后 5 分钟过期（兜底一致性）
     * recordStats：开启统计，方便监控命中率
     * </p>
     */
    @Bean
    public Cache<Long, SpuDetailVO> spuLocalCache() {
        Cache<Long, SpuDetailVO> cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("[Caffeine] SPU 本地缓存初始化完成, maximumSize=5000, TTL=5min");
        return cache;
    }

    /**
     * 分类树本地缓存
     * <p>
     * 分类数据极少变更，缓存 1 小时。
     * Key 固定为 "tree"，只缓存一份完整的分类树。
     * </p>
     */
    @Bean
    public Cache<String, List<CategoryTreeVO>> categoryTreeLocalCache() {
        Cache<String, List<CategoryTreeVO>> cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()
                .build();
        log.info("[Caffeine] 分类树本地缓存初始化完成, maximumSize=1, TTL=1h");
        return cache;
    }

    /**
     * 分类名称本地缓存（categoryId → categoryName）
     * <p>
     * 用于 SPU 详情查询时获取分类名称，避免每次都查 DB（解决 N+1 问题）。
     * 分类数据极少变更，缓存 1 小时，最多 500 个分类。
     * </p>
     */
    @Bean
    public Cache<Long, String> categoryNameLocalCache() {
        Cache<Long, String> cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()
                .build();
        log.info("[Caffeine] 分类名称本地缓存初始化完成, maximumSize=500, TTL=1h");
        return cache;
    }
}
