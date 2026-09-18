package com.myxhs.common.zone.locator;

/**
 * Zone 自动发现定位器：从部署环境推导当前 Zone（不依赖 Eureka/AWS 等特定平台）。
 *
 * @since 1.0.0
 */
public interface ZoneLocator {

    /**
     * 定位当前 Zone
     *
     * @return zone 名称；无法定位返回 null
     */
    String locate();

    /**
     * 顺序（小者优先）
     */
    default int getOrder() {
        return 0;
    }
}
