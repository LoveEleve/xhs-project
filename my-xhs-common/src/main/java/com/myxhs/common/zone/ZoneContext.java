package com.myxhs.common.zone;

import lombok.extern.slf4j.Slf4j;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Objects;
import java.util.StringJoiner;

import static com.myxhs.common.zone.ZoneConstants.CURRENT_ZONE_PROPERTY_NAME;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_ZONE;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_ZONE_ENABLED;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_ZONE_PREFERENCE_ENABLED;
import static com.myxhs.common.zone.ZoneConstants.DEFAULT_ZONE_PREFERENCE_FILTER_ORDER;

/**
 * Zone 上下文 — 单例状态管理器。
 * <p>
 * 管理当前实例所属的 Zone 以及 Zone 优先路由的配置参数。
 * 通过 {@link PropertyChangeSupport} 支持属性变更监听，供数据源热切换等场景使用。
 *
 * @since 1.0.0
 */
@Slf4j
public class ZoneContext {

    private static final ZoneContext INSTANCE = new ZoneContext();

    /** Zone 功能是否启用 */
    private volatile boolean enabled = DEFAULT_ZONE_ENABLED;

    /** 当前 Zone */
    private volatile String zone = DEFAULT_ZONE;

    /** Zone 优先路由是否启用 */
    private volatile boolean preferenceEnabled = DEFAULT_ZONE_PREFERENCE_ENABLED;

    /** Zone 优先过滤器顺序 */
    private volatile int preferenceFilterOrder = DEFAULT_ZONE_PREFERENCE_FILTER_ORDER;

    /** 上游 Zone 就绪百分比阈值 */
    private volatile int preferenceUpstreamZoneReadyPercentage = DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE;

    /** 同 Zone 最小可用实例数 */
    private volatile int preferenceUpstreamSameZoneMinAvailable = DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE;

    /** 禁用的上游 Zone（逗号分隔） */
    private volatile String preferenceUpstreamDisabledZone = DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE;

    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

    private ZoneContext() {
    }

    // ---- Setters ----

    public void setEnabled(boolean enabled) {
        if (isPropertyChanged("enabled", this.enabled, enabled)) {
            this.enabled = enabled;
        }
    }

    public void setZone(String zone) {
        if (isPropertyChanged("zone", this.zone, zone)) {
            this.zone = zone != null ? zone.trim() : null;
        }
    }

    public void setPreferenceEnabled(boolean preferenceEnabled) {
        if (isPropertyChanged("preferenceEnabled", this.preferenceEnabled, preferenceEnabled)) {
            this.preferenceEnabled = preferenceEnabled;
        }
    }

    public void setPreferenceFilterOrder(int preferenceFilterOrder) {
        if (isPropertyChanged("preferenceFilterOrder", this.preferenceFilterOrder, preferenceFilterOrder)) {
            this.preferenceFilterOrder = preferenceFilterOrder;
        }
    }

    public void setPreferenceUpstreamZoneReadyPercentage(int preferenceUpstreamZoneReadyPercentage) {
        if (isPropertyChanged("preferenceUpstreamZoneReadyPercentage",
                this.preferenceUpstreamZoneReadyPercentage, preferenceUpstreamZoneReadyPercentage)) {
            this.preferenceUpstreamZoneReadyPercentage = preferenceUpstreamZoneReadyPercentage;
        }
    }

    public void setPreferenceUpstreamSameZoneMinAvailable(int preferenceUpstreamSameZoneMinAvailable) {
        if (isPropertyChanged("preferenceUpstreamSameZoneMinAvailable",
                this.preferenceUpstreamSameZoneMinAvailable, preferenceUpstreamSameZoneMinAvailable)) {
            this.preferenceUpstreamSameZoneMinAvailable = preferenceUpstreamSameZoneMinAvailable;
        }
    }

    public void setPreferenceUpstreamDisabledZone(String preferenceUpstreamDisabledZone) {
        if (isPropertyChanged("preferenceUpstreamDisabledZone",
                this.preferenceUpstreamDisabledZone, preferenceUpstreamDisabledZone)) {
            this.preferenceUpstreamDisabledZone = resolveCommaDelimited(preferenceUpstreamDisabledZone);
        }
    }

    /**
     * 解析逗号分隔值，去除每个元素的空白
     */
    private String resolveCommaDelimited(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String[] parts = value.split(",");
        StringJoiner joiner = new StringJoiner(",");
        for (String part : parts) {
            joiner.add(part.trim());
        }
        return joiner.toString();
    }

    // ---- Listeners ----

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }

    // ---- Getters ----

    public boolean isEnabled() {
        return enabled;
    }

    public String getZone() {
        return zone;
    }

    public boolean isPreferenceEnabled() {
        return preferenceEnabled;
    }

    public int getPreferenceFilterOrder() {
        return preferenceFilterOrder;
    }

    public int getPreferenceUpstreamZoneReadyPercentage() {
        return preferenceUpstreamZoneReadyPercentage;
    }

    public int getPreferenceUpstreamSameZoneMinAvailable() {
        return preferenceUpstreamSameZoneMinAvailable;
    }

    public String getPreferenceUpstreamDisabledZone() {
        return preferenceUpstreamDisabledZone;
    }

    // ---- Operations ----

    /**
     * 启用 Zone 功能，返回之前的状态
     */
    public boolean enable() {
        boolean previous = this.enabled;
        if (!previous) {
            setEnabled(true);
        }
        return previous;
    }

    /**
     * 重置为默认值
     */
    public void reset() {
        setEnabled(DEFAULT_ZONE_ENABLED);
        setZone(DEFAULT_ZONE);
        setPreferenceEnabled(DEFAULT_ZONE_PREFERENCE_ENABLED);
        setPreferenceFilterOrder(DEFAULT_ZONE_PREFERENCE_FILTER_ORDER);
        setPreferenceUpstreamZoneReadyPercentage(DEFAULT_PREFERENCE_UPSTREAM_ZONE_READY_PERCENTAGE);
        setPreferenceUpstreamSameZoneMinAvailable(DEFAULT_PREFERENCE_UPSTREAM_SAME_ZONE_MIN_AVAILABLE);
        setPreferenceUpstreamDisabledZone(DEFAULT_PREFERENCE_UPSTREAM_DISABLED_ZONE);
    }

    private boolean isPropertyChanged(String propertyName, Object oldValue, Object newValue) {
        boolean changed = !Objects.equals(oldValue, newValue);
        if (changed) {
            propertyChangeSupport.firePropertyChange(propertyName, oldValue, newValue);
            log.info("Zone property '{}' changed: '{}' -> '{}'", propertyName, oldValue, newValue);
        }
        return changed;
    }

    // ---- Static ----

    /**
     * 获取单例 ZoneContext
     */
    public static ZoneContext get() {
        return INSTANCE;
    }

    /**
     * 获取当前 Zone（优先从系统属性读取）
     */
    public static String getCurrentZone() {
        return System.getProperty(CURRENT_ZONE_PROPERTY_NAME, DEFAULT_ZONE);
    }

    @Override
    public String toString() {
        return "ZoneContext{" +
                "enabled=" + enabled +
                ", zone='" + zone + '\'' +
                ", preferenceEnabled=" + preferenceEnabled +
                ", preferenceFilterOrder=" + preferenceFilterOrder +
                ", preferenceUpstreamZoneReadyPercentage=" + preferenceUpstreamZoneReadyPercentage +
                ", preferenceUpstreamSameZoneMinAvailable=" + preferenceUpstreamSameZoneMinAvailable +
                ", preferenceUpstreamDisabledZone=" + preferenceUpstreamDisabledZone +
                '}';
    }
}
