package com.myxhs.search.cache;

import com.myxhs.common.cache.CacheWarmer;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.search.job.RecommendComputeJob;
import com.myxhs.search.service.HotSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 搜索/推荐域缓存预热任务
 * <p>
 * 预热两个榜单缓存（均为 1h TTL 的 ZSet，天然适合启动预热）：
 * 1. 热搜实时榜：myxhs:search:hot:realtime（数据源 = 搜索行为日志聚合）
 * 2. 推荐热门池：myxhs:recommend:hot:global（数据源 = 最近 24h 交互行为）
 * </p>
 * <p>
 * 两者都是"定时任务周期性重建"的派生缓存：启动时若不存在（重启后 Redis 为空、
 * 或距上次重建已过 TTL），立即按同一套计算逻辑重建一次，避免首屏读榜单为空。
 * 缓存已存在则跳过，不做重复计算。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchCacheWarmer implements CacheWarmer {

    private final HotSearchService hotSearchService;
    private final RecommendComputeJob recommendComputeJob;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String name() {
        return "search-hot-and-recommend";
    }

    @Override
    public int warm() {
        int warmed = 0;
        if (warmHotSearchRank()) {
            warmed++;
        }
        if (warmRecommendHotPool()) {
            warmed++;
        }
        return warmed;
    }

    /**
     * 热搜实时榜：缓存缺失时触发一次全量计算
     */
    private boolean warmHotSearchRank() {
        if (exists(RedisKeyConstants.SEARCH_HOT_REALTIME)) {
            log.debug("[缓存预热] 热搜榜已存在，跳过");
            return false;
        }
        hotSearchService.calculateHotSearch();
        log.info("[缓存预热] 热搜实时榜已重建");
        return true;
    }

    /**
     * 推荐热门池：缓存缺失时触发一次全量计算
     * <p>
     * 计算过程是"写临时 Key → RENAME 替换"，与定时任务并发执行会互相踩（二次 RENAME 因临时 Key 已消失而报错），
     * 因此用 SETNX 抢占预热锁，抢不到说明已有预热/任务在跑，本轮跳过。
     * </p>
     */
    private boolean warmRecommendHotPool() {
        if (exists(RedisKeyConstants.RECOMMEND_HOT_GLOBAL)) {
            log.debug("[缓存预热] 推荐热门池已存在，跳过");
            return false;
        }
        String lockKey = "myxhs:lock:cache-warmup:recommend-hot-pool";
        String token = java.util.UUID.randomUUID().toString();
        Boolean locked;
        try {
            locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[缓存预热] 推荐热门池抢锁失败(Redis 不可用)，跳过");
            return false;
        }
        if (!Boolean.TRUE.equals(locked)) {
            log.info("[缓存预热] 推荐热门池已有实例在预热，跳过");
            return false;
        }
        try {
            recommendComputeJob.warmUpHotPool();
            log.info("[缓存预热] 推荐热门池已重建");
            return true;
        } finally {
            stringRedisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class), java.util.List.of(lockKey), token);
        }
    }

    private boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            // Redis 不可用时不做预热，后续按需加载
            return true;
        }
    }
}
