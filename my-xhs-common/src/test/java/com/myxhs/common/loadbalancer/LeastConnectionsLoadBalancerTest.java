package com.myxhs.common.loadbalancer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 最少活跃负载均衡测试（含平局分散回归）
 */
class LeastConnectionsLoadBalancerTest {

    private ServiceInstance instance(int port) {
        return new DefaultServiceInstance("i" + port, "my-xhs-product", "127.0.0.1", port, false);
    }

    private ObjectProvider<ServiceInstanceListSupplier> provider(List<ServiceInstance> instances) {
        ServiceInstanceListSupplier supplier = new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "my-xhs-product";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(instances);
            }
        };
        return new ObjectProvider<>() {
            @Override
            public ServiceInstanceListSupplier getObject() {
                return supplier;
            }

            @Override
            public ServiceInstanceListSupplier getObject(Object... args) {
                return supplier;
            }

            @Override
            public ServiceInstanceListSupplier getIfAvailable() {
                return supplier;
            }

            @Override
            public ServiceInstanceListSupplier getIfAvailable(Supplier<ServiceInstanceListSupplier> defaultSupplier) {
                return supplier;
            }

            @Override
            public ServiceInstanceListSupplier getIfUnique() {
                return supplier;
            }

            @Override
            public ServiceInstanceListSupplier getIfUnique(Supplier<ServiceInstanceListSupplier> defaultSupplier) {
                return supplier;
            }
        };
    }

    private ServiceInstance pick(LeastConnectionsLoadBalancer lb) {
        return lb.choose(null).block().getServer();
    }

    @Test
    void testTieBreakSpreadsAcrossInstances() {
        LeastConnectionsLoadBalancer lb = new LeastConnectionsLoadBalancer(
                provider(List.of(instance(19006), instance(19026))), "my-xhs-product");
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ports.add(pick(lb).getPort());
        }
        assertEquals(2, ports.size(), "平局时应随机分散，不能恒定选第一个实例");
    }

    @Test
    void testLeastActiveWins() {
        ServiceInstance busy = instance(19006);
        LeastConnectionsLoadBalancer lb = new LeastConnectionsLoadBalancer(
                provider(List.of(busy, instance(19026))), "my-xhs-product");
        for (int i = 0; i < 50; i++) {
            lb.markRequestStart(busy);
        }
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ports.add(pick(lb).getPort());
        }
        assertEquals(Set.of(19026), ports, "活跃数高的实例不应被选中");
    }
}
