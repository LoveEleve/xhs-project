package com.myxhs.common.zone.loadbalancer;

import com.myxhs.common.zone.ZoneConstants;
import com.myxhs.common.zone.ZoneResolver;
import org.springframework.cloud.client.ServiceInstance;

import java.util.Map;

/**
 * Spring Cloud ServiceInstance Zone 解析器。
 * <p>
 * 从 ServiceInstance 的 Nacos metadata 中读取 zone 信息。
 *
 * @since 1.0.0
 */
public class ServiceInstanceZoneResolver implements ZoneResolver<ServiceInstance> {

    public static final ServiceInstanceZoneResolver INSTANCE = new ServiceInstanceZoneResolver();

    @Override
    public String resolve(ServiceInstance serviceInstance) {
        if (serviceInstance == null) {
            return null;
        }
        Map<String, String> metadata = serviceInstance.getMetadata();
        if (metadata != null) {
            String zone = metadata.get(ZoneConstants.METADATA_ZONE_KEY);
            if (zone == null || zone.isEmpty()) {
                zone = metadata.get(ZoneConstants.ZONE_PROPERTY_NAME);
            }
            return (zone == null || zone.isEmpty()) ? null : zone;
        }
        return null;
    }
}
