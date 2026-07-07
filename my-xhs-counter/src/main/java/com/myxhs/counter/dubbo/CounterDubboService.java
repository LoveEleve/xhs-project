package com.myxhs.counter.dubbo;

import java.util.Map;

/**
 * 计数 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于内容服务高频调用点赞/评论计数链路。
 * </p>
 */
public interface CounterDubboService {

    /**
     * 计数 +1（点赞、收藏等）
     *
     * @param targetType 目标类型（1=笔记, 2=评论）
     * @param targetId   目标 ID
     * @param countType  计数类型（1=点赞, 2=收藏, 3=评论数）
     */
    void increment(int targetType, long targetId, int countType);

    /**
     * 计数 -1（取消点赞、取消收藏等）
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param countType  计数类型
     * @return true=成功, false=归零保护拦截
     */
    boolean decrement(int targetType, long targetId, int countType);

    /**
     * 查询单个计数
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param countType  计数类型
     * @return 计数值
     */
    long getCount(int targetType, long targetId, int countType);

    /**
     * 批量查询计数（Pipeline 优化）
     * <p>
     * 返回格式：Map<"targetType:targetId", Map<countTypeEnglishName, count>>
     * 示例：{"1:20001": {"like": 42, "collect": 18}}
     * </p>
     *
     * @param request 包含 "items" 键的 Map，每个 item 包含 targetType, targetId, countTypes
     * @return 批量计数结果
     */
    Map<String, Map<String, Long>> batchGetCounts(Map<String, Object> request);
}
