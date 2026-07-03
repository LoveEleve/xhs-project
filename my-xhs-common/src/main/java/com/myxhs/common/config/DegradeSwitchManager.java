package com.myxhs.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 降级开关管理器
 * <p>
 * 使用 Nacos Config + @RefreshScope 实现动态降级切换。
 * 运维可在 Nacos Console 修改 my-xhs-degrade-switches.yml，无需重启服务即可生效。
 * </p>
 * <p>
 * 配置示例（Nacos my-xhs-degrade-switches.yml）：
 * <pre>
 * degrade:
 *   recommend-degrade: false   # 推荐降级 → 返回热门兜底
 *   feed-degrade: false         # Feed 流降级 → 返回缓存数据
 *   notification-degrade: false # 通知降级 → 暂停推送
 *   search-degrade: false       # 搜索降级 → 返回热搜兜底
 *   hot-fallback: false         # 开启全站热门兜底
 * </pre>
 * </p>
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "degrade")
public class DegradeSwitchManager {

    /** 推荐服务降级开关：true=降级返回热门兜底 */
    private boolean recommendDegrade = false;

    /** Feed 流降级开关：true=降级返回缓存数据 */
    private boolean feedDegrade = false;

    /** 通知降级开关：true=暂停推送 */
    private boolean notificationDegrade = false;

    /** 搜索降级开关：true=返回热搜兜底 */
    private boolean searchDegrade = false;

    /** 全站热门兜底开关：true=所有未命中请求走热门内容 */
    private boolean hotFallback = false;
}
