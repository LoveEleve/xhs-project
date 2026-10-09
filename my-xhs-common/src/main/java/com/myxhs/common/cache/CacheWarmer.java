package com.myxhs.common.cache;

/**
 * 缓存预热扩展点（SPI）
 * <p>
 * 各业务域实现本接口，把"启动预热"逻辑注册为 Spring Bean：
 * - 谁的数据谁预热：预热任务只操作本域的库表与缓存键，不跨域写别人的缓存；
 * - 任务名用于 {@code myxhs.cache-warmup.targets} 精确选择（逗号分隔，为空表示本服务全部任务）；
 * - 预热失败只记日志、不阻塞启动，缓存由按需加载自然回填。
 * </p>
 * <p>
 * 场景：服务重启后热点缓存为空，首屏请求会集中回源数据库（冷启动穿透）。
 * 启动期预热把这一步提前到流量到来之前，代价是少量启动耗时。
 * </p>
 */
public interface CacheWarmer {

    /**
     * 预热任务名（如 product-category-tree），供 myxhs.cache-warmup.targets 选择
     */
    String name();

    /**
     * 执行预热
     *
     * @return 预热条目数（0 表示缓存已存在或暂无可预热数据）
     */
    int warm() throws Exception;

    /**
     * 是否启用，默认启用（子类可按开关覆盖）
     */
    default boolean enabled() {
        return true;
    }
}
