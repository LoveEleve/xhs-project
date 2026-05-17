package com.myxhs.search.recommend;

import com.myxhs.search.dto.RecallItem;

import java.util.List;

/**
 * 召回策略接口
 * <p>
 * 每路召回策略独立实现此接口，返回候选笔记列表。
 * 各策略之间互不依赖，可并行执行。
 * </p>
 */
public interface RecallStrategy {

    /**
     * 执行召回
     *
     * @param userId 用户ID
     * @param size   期望返回的候选数量
     * @return 候选笔记列表
     */
    List<RecallItem> recall(Long userId, int size);

    /**
     * 策略名称（用于日志和监控）
     */
    String name();
}
