package com.myxhs.common.zone.loadbalancer;

import com.myxhs.common.zone.ZonePreferenceFilter;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Zone 优先 ServiceInstanceListSupplier。
 * <p>
 * 扩展 DelegatingServiceInstanceListSupplier，在获取服务实例列表后
 * 通过 ZonePreferenceFilter 进行 Zone 优先过滤。
 *
 * @since 1.0.0
 */
public class ZonePreferenceServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private final ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter;

    public ZonePreferenceServiceInstanceListSupplier(
            ServiceInstanceListSupplier delegate,
            ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter) {
        super(delegate);
        this.zonePreferenceFilter = zonePreferenceFilter;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(this::filterByZone);
    }

    private List<ServiceInstance> filterByZone(List<ServiceInstance> serviceInstances) {
        return zonePreferenceFilter.filter(serviceInstances);
    }
}
