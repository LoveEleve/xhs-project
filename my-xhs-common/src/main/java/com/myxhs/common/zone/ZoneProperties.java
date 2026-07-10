package com.myxhs.common.zone;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Zone 多活配置属性
 *
 * @since 1.0.0
 */
@Data
@ConfigurationProperties(prefix = "myxhs.availability.zone")
public class ZoneProperties {

    /** Zone 功能是否启用 */
    private boolean enabled = ZoneConstants.DEFAULT_ZONE_ENABLED;

    /** Zone 优先路由配置 */
    @NestedConfigurationProperty
    private PreferenceProperties preference = new PreferenceProperties();

    @Data
    public static class PreferenceProperties {

        /** Zone 优先路由是否启用 */
        private boolean enabled = ZoneConstants.DEFAULT_ZONE_PREFERENCE_ENABLED;

        /** Zone 优先过滤器配置 */
        @NestedConfigurationProperty
        private FilterProperties filter = new FilterProperties();

        /** 上游配置 */
        @NestedConfigurationProperty
        private UpstreamProperties upstream = new UpstreamProperties();
    }

    @Data
    public static class FilterProperties {

        /** 过滤器顺序 */
        private int order = ZoneConstants.DEFAULT_ZONE_PREFERENCE_FILTER_ORDER;
    }

    @Data
    public static class UpstreamProperties {

        /** 上游 Zone 就绪百分比阈值 */
        private int zoneReadyPercentage = ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE;

        /** 同 Zone 最小可用实例数 */
        private int sameZoneMinAvailable = ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE;

        /** 禁用的上游 Zone（逗号分隔） */
        private String disabledZone = ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE;
    }
}
