package com.myxhs.common.zone;

/**
 * Zone 多活架构常量定义
 *
 * @since 1.0.0
 */
public interface ZoneConstants {

    String ENABLED_PROPERTY_NAME_SUFFIX = ".enabled";

    /**
     * Zone 属性名
     */
    String ZONE_PROPERTY_NAME = "myxhs.availability.zone";

    /**
     * 当前 Zone 属性名（系统属性）
     */
    String CURRENT_ZONE_PROPERTY_NAME = "myxhs.current.availability.zone";

    /**
     * Zone 是否启用属性名
     */
    String ZONE_ENABLED_PROPERTY_NAME = ZONE_PROPERTY_NAME + ENABLED_PROPERTY_NAME_SUFFIX;

    // Zone Preference 属性

    String PREFERENCE_PROPERTY_NAME_PREFIX = ZONE_PROPERTY_NAME + ".preference";

    String PREFERENCE_ENABLED_PROPERTY_NAME = PREFERENCE_PROPERTY_NAME_PREFIX + ENABLED_PROPERTY_NAME_SUFFIX;

    String PREFERENCE_FILTER_PROPERTY_NAME_PREFIX = PREFERENCE_PROPERTY_NAME_PREFIX + ".filter";

    String PREFERENCE_FILTER_ORDER_PROPERTY_NAME = PREFERENCE_FILTER_PROPERTY_NAME_PREFIX + ".order";

    String PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX = PREFERENCE_PROPERTY_NAME_PREFIX + ".upstream";

    String PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE_PROPERTY_NAME =
            PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX + ".zone-ready-percentage";

    String PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE_PROPERTY_NAME =
            PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX + ".same-zone-min-available";

    String PREFERENCE_UPSTREAM_DISABLED_ZONE_PROPERTY_NAME =
            PREFERENCE_UPSTREAM_PROPERTY_NAME_PREFIX + ".disabled-zone";

    // Zone Locator 属性

    String LOCATOR_PROPERTY_NAME_PREFIX = ZONE_PROPERTY_NAME + ".locator";

    String LOCATOR_FAST_FAIL_PROPERTY_NAME = LOCATOR_PROPERTY_NAME_PREFIX + ".fast-fail";

    String LOCATOR_TIMEOUT_PROPERTY_NAME = LOCATOR_PROPERTY_NAME_PREFIX + ".timeout";

    // 默认值

    /**
     * Zone 功能默认启用
     */
    boolean DEFAULT_ZONE_ENABLED = true;

    /**
     * Zone 优先默认不启用（需显式配置）
     */
    boolean DEFAULT_ZONE_PREFERENCE_ENABLED = Boolean.getBoolean(PREFERENCE_ENABLED_PROPERTY_NAME);

    /**
     * Zone 优先过滤器默认顺序
     */
    int DEFAULT_ZONE_PREFERENCE_FILTER_ORDER = 10;

    /**
     * 默认 Zone 名称
     */
    String DEFAULT_ZONE = "defaultZone";

    /**
     * 上游 Zone 就绪百分比默认值
     */
    int DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE = 100;

    /**
     * 同 Zone 最小可用实例数默认值
     */
    int DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE = 5;

    /**
     * 禁用的上游 Zone 默认值
     */
    String DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE = null;

    /**
     * Locator 快速失败默认值
     */
    boolean DEFAULT_LOCATOR_FAST_FAIL = false;

    /**
     * Locator 超时默认值（3秒）
     */
    int DEFAULT_LOCATOR_TIMEOUT = 3000;

}
