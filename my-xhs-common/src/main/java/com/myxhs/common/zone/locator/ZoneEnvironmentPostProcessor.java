package com.myxhs.common.zone.locator;

import com.myxhs.common.zone.ZoneConstants;
import com.myxhs.common.zone.ZoneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Zone 自动发现入口（EnvironmentPostProcessor，注册前生效）。
 * <p>
 * 在应用环境准备阶段定位当前 Zone，并写入
 * {@code spring.cloud.nacos.discovery.metadata.zone}，使 Nacos 注册元数据携带自动发现的 Zone。
 * </p>
 * <p>
 * 优先级：显式系统属性 {@code myxhs.current.availability.zone} > 显式 metadata.zone（非 defaultZone）
 * > 定位器链（env MYXHS_ZONE → zone 文件 → 网段映射） > defaultZone。
 * </p>
 *
 * @since 1.0.0
 */
public class ZoneEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String METADATA_ZONE_PROPERTY = "spring.cloud.nacos.discovery.metadata.zone";
    private static final String LOCATOR_PREFIX = "myxhs.availability.zone.locator";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Boolean enabled = environment.getProperty(LOCATOR_PREFIX + ".enabled", Boolean.class, Boolean.FALSE);
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }

        String explicit = System.getProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME);
        if (explicit != null && !explicit.isBlank()) {
            return;
        }
        String metadataZone = environment.getProperty(METADATA_ZONE_PROPERTY);
        if (metadataZone != null && !metadataZone.isBlank() && !ZoneConstants.DEFAULT_ZONE.equals(metadataZone)) {
            return;
        }

        ZoneProperties.LocatorProperties properties = Binder.get(environment)
                .bind(LOCATOR_PREFIX, ZoneProperties.LocatorProperties.class)
                .orElseGet(ZoneProperties.LocatorProperties::new);

        List<ZoneLocator> locators = new ArrayList<>();
        locators.add(new EnvVarZoneLocator());
        locators.add(new FileZoneLocator(properties.getFile()));
        locators.add(new IpRangeZoneLocator(properties.getIpRanges()));

        String zone = new CompositeZoneLocator(locators).locate();
        if (zone != null && !zone.isBlank()) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "myxhsZoneDiscovery", Map.of(METADATA_ZONE_PROPERTY, zone)));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
