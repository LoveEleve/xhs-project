package com.myxhs.common.zone;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * ZoneContext 自动配置。
 * <p>
 * 从 Spring 环境变量读取 Zone 配置并初始化 ZoneContext。
 *
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myxhs.availability.zone", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ZoneContextAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "myxhs.availability.zone")
    public ZoneProperties zoneProperties() {
        return new ZoneProperties();
    }

    @Bean
    public ZoneContext zoneContext(ZoneProperties zoneProperties, Environment environment) {
        ZoneContext zoneContext = ZoneContext.get();

        zoneContext.setEnabled(zoneProperties.isEnabled());
        zoneContext.setPreferenceEnabled(zoneProperties.getPreference().isEnabled());
        zoneContext.setPreferenceFilterOrder(zoneProperties.getPreference().getFilter().getOrder());
        zoneContext.setPreferenceUpstreamZoneReadyPercentage(
                zoneProperties.getPreference().getUpstream().getZoneReadyPercentage());
        zoneContext.setPreferenceUpstreamSameZoneMinAvailable(
                zoneProperties.getPreference().getUpstream().getSameZoneMinAvailable());
        zoneContext.setPreferenceUpstreamDisabledZone(
                zoneProperties.getPreference().getUpstream().getDisabledZone());

        // Zone 优先从系统属性获取，fallback 到配置
        String zone = System.getProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME,
                environment.getProperty("spring.cloud.nacos.discovery.metadata.zone", "defaultZone"));
        zoneContext.setZone(zone);

        return zoneContext;
    }
}
