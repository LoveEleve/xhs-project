package com.myxhs.product.cache;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Redis 逻辑过期缓存数据包装
 * <p>
 * 【设计决策：逻辑过期 vs 物理过期】
 * 物理过期：Key 到期后 Redis 自动删除 → 高并发下大量请求同时穿透到 DB（缓存击穿）
 * 逻辑过期：Key 永不物理过期，Value 中包含逻辑过期时间 → 发现过期返回旧值 + 异步刷新
 * <p>
 * 商品详情页允许秒级延迟（价格变更延迟几秒用户无感知），但不允许缓存击穿打挂 DB。
 * 因此选择逻辑过期方案。
 * </p>
 *
 * @param <T> 实际数据类型
 */
@Data
public class RedisCacheData<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实际缓存数据 */
    private T data;

    /** 逻辑过期时间 */
    private LocalDateTime logicExpire;

    public RedisCacheData() {
    }

    public RedisCacheData(T data, LocalDateTime logicExpire) {
        this.data = data;
        this.logicExpire = logicExpire;
    }

    /**
     * 是否已逻辑过期
     */
    public boolean isExpired() {
        return logicExpire != null && LocalDateTime.now().isAfter(logicExpire);
    }

    /**
     * 创建带逻辑过期时间的缓存数据
     *
     * @param data    实际数据
     * @param minutes 过期分钟数
     */
    public static <T> RedisCacheData<T> of(T data, long minutes) {
        return new RedisCacheData<>(data, LocalDateTime.now().plusMinutes(minutes));
    }
}
