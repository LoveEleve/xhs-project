package com.myxhs.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 降级开关管理器
 * <p>
 * 使用 DynamicConfigRefresher（EnvironmentChangeEvent + @ConfigurationProperties）替代 @RefreshScope 实现动态降级切换。
 * 运维可在 Nacos Console 修改 myxhs.dynamic.degrade 配置，无需重启服务即可生效。
 * </p>
 * <p>
 * 配置示例（Nacos myxhs-dynamic.yml）：
 * <pre>
 * myxhs:
 *   dynamic:
 *     degrade:
 *       recommend-degrade: false   # 推荐降级 → 返回热门兜底
 *       feed-degrade: false         # Feed 流降级 → 返回缓存数据
 *       notification-degrade: false # 通知降级 → 暂停推送
 *       search-degrade: false       # 搜索降级 → 返回热搜兜底
 *       hot-fallback: false         # 开启全站热门兜底
 * </pre>
 * </p>
 */
@Slf4j
@Component
public class DegradeSwitchManager {

    @Autowired
    private DynamicConfigRefresher dynamicConfigRefresher;

    private static final String DEGRADE_PREFIX = "degrade.";

    /** 推荐服务降级开关：true=降级返回热门兜底 */
    public boolean isRecommendDegrade() {
        return dynamicConfigRefresher.isDegraded("recommend-degrade");
    }

    /** Feed 流降级开关：true=降级返回缓存数据 */
    public boolean isFeedDegrade() {
        return dynamicConfigRefresher.isDegraded("feed-degrade");
    }

    /** 通知降级开关：true=暂停推送 */
    public boolean isNotificationDegrade() {
        return dynamicConfigRefresher.isDegraded("notification-degrade");
    }

    /** 搜索降级开关：true=返回热搜兜底 */
    public boolean isSearchDegrade() {
        return dynamicConfigRefresher.isDegraded("search-degrade");
    }

    /** 全站热门兜底开关：true=所有未命中请求走热门内容 */
    public boolean isHotFallback() {
        return dynamicConfigRefresher.isDegraded("hot-fallback");
    }
}
