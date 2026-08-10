package com.myxhs.inventory.hot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 热点 SKU 检测器（滑动窗口 + ZSet 计数）
 * <p>
 * 记录每次预扣请求到 Redis ZSet（score=秒级时间戳），
 * 滑动窗口内请求数超过阈值时判定为热点。
 * </p>
 * <p>
 * 内存控制：score 使用秒级时间戳，ZREMRANGEBYSCORE 定期清理窗口外数据 + TTL 兜底。
 * member 使用"秒:线程ID:nanoTime"唯一后缀——每次请求一个独立 member，
 * 通过 ZCARD 统计窗口内请求数（不合并同秒请求，保证计数精确）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotSkuDetector {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String HOT_WINDOW_PREFIX = "inventory:hot:window:";

    /** 窗口长度（秒），默认 10 秒 */
    private static final int WINDOW_SECONDS = 10;

    /** 热点阈值：窗口内请求数 >= 此值视为热点 */
    private static final int HOT_THRESHOLD = 100;

    /** 窗口 Key 过期时间（秒），避免冷 SKU 的 Key 永久占用内存 */
    private static final int WINDOW_TTL_SECONDS = 30;

    /**
     * 记录一次预扣请求并判断是否为热点
     *
     * @param skuId SKU ID
     * @return true=热点，false=非热点
     */
    public boolean recordAndCheck(Long skuId) {
        String key = HOT_WINDOW_PREFIX + skuId;
        long nowSec = System.currentTimeMillis() / 1000;

        // 1. ZADD：使用秒级时间戳 + 随机后缀作为 member（防完全重复覆盖）
        stringRedisTemplate.opsForZSet().add(key,
                nowSec + ":" + Thread.currentThread().getId() + ":" + System.nanoTime(),
                nowSec);

        // 2. ZREMRANGEBYSCORE：删除窗口外的历史数据
        stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, nowSec - WINDOW_SECONDS);

        // 3. ZCARD：获取窗口内请求数
        Long count = stringRedisTemplate.opsForZSet().zCard(key);

        // 4. EXPIRE：防止冷 SKU 的 Key 永久占用内存
        stringRedisTemplate.expire(key, WINDOW_TTL_SECONDS, TimeUnit.SECONDS);

        return count != null && count >= HOT_THRESHOLD;
    }
}
