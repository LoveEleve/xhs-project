package com.myxhs.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 搜索历史服务
 * <p>
 * 基于 Redis List 存储用户搜索历史（最近 20 条）。
 * 数据结构：LPUSH + LTRIM，新搜索词插入头部，超出 20 条自动裁剪。
 * 去重：搜索前先 LREM 删除已存在的相同关键词，再 LPUSH。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryService {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${search.history.max-size:20}")
    private int maxSize;

    @Value("${search.history.ttl-days:30}")
    private int ttlDays;

    private static final String HISTORY_KEY_PREFIX = "myxhs:search:history:";

    /**
     * 记录搜索历史（RV34：补齐写入路径——原实现只有读/删，历史从未落 Redis）
     * LPUSH + LTRIM 裁剪 + LREM 去重 + EXPIRE（ttl-days）
     */
    public void addHistory(Long userId, String keyword) {
        if (userId == null || userId <= 0 || keyword == null || keyword.isBlank()) {
            return;
        }
        String key = HISTORY_KEY_PREFIX + userId;
        try {
            stringRedisTemplate.opsForList().remove(key, 0, keyword);
            stringRedisTemplate.opsForList().leftPush(key, keyword);
            stringRedisTemplate.opsForList().trim(key, 0, maxSize - 1);
            stringRedisTemplate.expire(key, java.time.Duration.ofDays(ttlDays));
        } catch (Exception e) {
            log.warn("[搜索历史] 写入失败: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 获取搜索历史（最近 20 条）
     */
    public List<String> getHistory(Long userId) {
        String key = HISTORY_KEY_PREFIX + userId;
        List<String> history = stringRedisTemplate.opsForList().range(key, 0, maxSize - 1);
        return history != null ? history : Collections.emptyList();
    }

    /**
     * 清空搜索历史
     */
    public void clearHistory(Long userId) {
        String key = HISTORY_KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
    }

    /**
     * 删除单条搜索历史
     */
    public void deleteHistoryItem(Long userId, String keyword) {
        String key = HISTORY_KEY_PREFIX + userId;
        stringRedisTemplate.opsForList().remove(key, 0, keyword);
    }
}
