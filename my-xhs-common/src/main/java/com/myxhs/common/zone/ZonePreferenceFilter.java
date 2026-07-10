package com.myxhs.common.zone;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.myxhs.common.zone.ZoneConstants.DEFAULT_ZONE;
import static com.myxhs.common.zone.ZoneConstants.PREFERENCE_ENABLED_PROPERTY_NAME;
import static com.myxhs.common.zone.ZoneConstants.ZONE_ENABLED_PROPERTY_NAME;

/**
 * Zone 优先过滤器 — 核心路由算法。
 * <p>
 * 10 步决策流程：
 * <ol>
 *   <li>检查实体列表是否为空或仅 1 个 → 直接返回</li>
 *   <li>检查 Zone 功能是否启用 → 未启用直接返回全部</li>
 *   <li>检查 Zone 优先是否启用 → 未启用直接返回全部</li>
 *   <li>检查当前 Zone 是否为无效值 → 忽略优先逻辑</li>
 *   <li>过滤禁用 Zone 的实体 → 剩余不足时返回全部</li>
 *   <li>按 Zone 分组统计同 Zone 实体</li>
 *   <li>检查上游 Zone 就绪百分比是否达标 → 未达标返回全部</li>
 *   <li>检查同 Zone 实体数是否满足最小可用阈值 → 不满足返回全部</li>
 *   <li>同 Zone 实体数 > 0 → 返回同 Zone 实体</li>
 *   <li>无同 Zone 实体 → 返回全部（fallback）</li>
 * </ol>
 *
 * @param <E> 实体类型
 * @since 1.0.0
 */
@Slf4j
public class ZonePreferenceFilter<E> {

    private final ZoneContext zoneContext;
    private final ZoneResolver<E> zoneResolver;

    public ZonePreferenceFilter(ZoneContext zoneContext, ZoneResolver<E> zoneResolver) {
        this.zoneContext = zoneContext;
        this.zoneResolver = zoneResolver;
    }

    /**
     * 执行 Zone 优先过滤
     *
     * @param entities 原始实体列表
     * @return 过滤后的实体列表
     */
    public List<E> filter(final List<E> entities) {
        int totalSize = (entities == null) ? 0 : entities.size();

        // 1. 空列表或单元素，无需过滤
        if (totalSize <= 1) {
            return entities;
        }

        // 2. Zone 功能未启用
        if (!zoneContext.isEnabled()) {
            log.debug("Zone feature disabled. Enable via '{}'", ZONE_ENABLED_PROPERTY_NAME);
            return entities;
        }

        // 3. Zone 优先未启用
        if (!zoneContext.isPreferenceEnabled()) {
            log.debug("Zone preference disabled. Enable via '{}'", PREFERENCE_ENABLED_PROPERTY_NAME);
            return entities;
        }

        // 4. 当前 Zone 为无效值
        final String zone = zoneContext.getZone();
        if (isIgnored(zone)) {
            log.debug("Zone preference ignored, current zone: '{}'", zone);
            return entities;
        }

        List<E> targetEntities = entities;

        // 5. 过滤禁用 Zone 的实体
        String disabledZone = zoneContext.getPreferenceUpstreamDisabledZone();
        if (disabledZone != null && !disabledZone.isEmpty()) {
            targetEntities = filterDisabledZone(entities, disabledZone, totalSize);
            int currentSize = targetEntities.size();
            if (currentSize <= 1) {
                log.debug("Not enough entities after disabled zone filter, size: {} -> {}", totalSize, currentSize);
                return entities;
            }
            totalSize = currentSize;
        }

        // 6. 按 Zone 分组
        List<E> sameZoneEntities = new ArrayList<>();
        int zoneCount = 0;

        for (int i = 0; i < totalSize; i++) {
            E entity = targetEntities.get(i);
            String resolvedZone = resolveZone(entity);
            if (resolvedZone != null) {
                zoneCount++;
                if (matches(zone, resolvedZone)) {
                    sameZoneEntities.add(entity);
                }
            }
        }

        // 7. 检查上游 Zone 就绪百分比
        int upstreamReadyPercentage = zoneContext.getPreferenceUpstreamZoneReadyPercentage();
        if (isUpstreamZoneNotReady(zoneCount, totalSize, upstreamReadyPercentage)) {
            log.debug("Upstream zone ready percentage under threshold [{}%], total: {}, ready: {}",
                    upstreamReadyPercentage, totalSize, zoneCount);
            return targetEntities;
        }

        // 8-9. 同 Zone 实体满足最小可用阈值 → 返回同 Zone 实体
        int sameZoneSize = sameZoneEntities.size();
        if (sameZoneSize > 0) {
            int sameZoneMinAvailable = zoneContext.getPreferenceUpstreamSameZoneMinAvailable();
            if (isUnderSameZoneMinAvailableThreshold(sameZoneSize, sameZoneMinAvailable)) {
                log.debug("Same zone '{}' entities under threshold: {}, actual: {}", zone, sameZoneMinAvailable, sameZoneSize);
                return targetEntities;
            }
            log.debug("Same zone '{}' entities found: {}/{}", zone, sameZoneSize, totalSize);
            return sameZoneEntities;
        }

        // 10. 无同 Zone 实体 → 返回全部
        log.debug("No same zone '{}' entity found, total: {}, zone count: {}", zone, totalSize, zoneCount);
        return targetEntities;
    }

    public int getOrder() {
        return zoneContext.getPreferenceFilterOrder();
    }

    /**
     * 过滤掉属于禁用 Zone 的实体
     */
    private List<E> filterDisabledZone(List<E> entities, String disabledZone, int totalSize) {
        String[] disabledZones = disabledZone.split(",");
        List<E> result = new ArrayList<>();
        for (int i = 0; i < totalSize; i++) {
            E entity = entities.get(i);
            String resolvedZone = resolveZone(entity);
            if (!isDisabledZone(resolvedZone, disabledZones)) {
                result.add(entity);
            }
        }
        log.debug("Disabled zone '{}' filter: {} -> {}", disabledZone, totalSize, result.size());
        return result;
    }

    private boolean isDisabledZone(String zone, String[] disabledZones) {
        for (String dz : disabledZones) {
            if (Objects.equals(zone, dz.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查上游 Zone 就绪百分比是否不达标
     */
    private boolean isUpstreamZoneNotReady(int zoneCount, int totalSize, int threshold) {
        int percent = (zoneCount * 100 / totalSize);
        return percent < threshold;
    }

    /**
     * 检查同 Zone 实例数是否低于最小可用阈值
     */
    private boolean isUnderSameZoneMinAvailableThreshold(int sameZoneSize, int threshold) {
        return sameZoneSize < threshold;
    }

    private String resolveZone(E entity) {
        return (entity == null) ? null : zoneResolver.resolve(entity);
    }

    private boolean matches(String zone, String resolvedZone) {
        return Objects.equals(zone, resolvedZone);
    }

    /**
     * 判断 Zone 值是否应被忽略（空值或默认值）
     */
    protected boolean isIgnored(String zone) {
        return zone == null || zone.isBlank() || DEFAULT_ZONE.equalsIgnoreCase(zone);
    }
}
