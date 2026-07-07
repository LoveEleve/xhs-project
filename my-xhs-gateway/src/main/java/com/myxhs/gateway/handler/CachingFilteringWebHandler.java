package com.myxhs.gateway.handler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 缓存合并后的 GatewayFilter 列表，避免每次请求都创建 ArrayList 并排序。
 * 直接实现 WebHandler（而非继承 FilteringWebHandler，因其 DefaultGatewayFilterChain 为 private）。
 */
public class CachingFilteringWebHandler implements WebHandler {

    protected static final Log logger = LogFactory.getLog(CachingFilteringWebHandler.class);

    private final List<GatewayFilter> globalFilters;
    private final Map<String, List<GatewayFilter>> filterCache = new ConcurrentHashMap<>();

    public CachingFilteringWebHandler(List<GlobalFilter> globalFilters) {
        this.globalFilters = loadFilters(globalFilters);
    }

    private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
        return filters.stream().map(filter -> {
            GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);
            if (filter instanceof Ordered) {
                int order = ((Ordered) filter).getOrder();
                return new OrderedGatewayFilter(gatewayFilter, order);
            }
            return gatewayFilter;
        }).collect(Collectors.toList());
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange) {
        Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
        String routeId = route.getId();

        List<GatewayFilter> combined = filterCache.computeIfAbsent(routeId, id -> {
            List<GatewayFilter> merged = new ArrayList<>(this.globalFilters);
            merged.addAll(route.getFilters());
            AnnotationAwareOrderComparator.sort(merged);
            if (logger.isDebugEnabled()) {
                logger.debug("Cached sorted gatewayFilters for route '" + routeId + "': " + merged);
            }
            return Collections.unmodifiableList(merged);
        });

        return new SimpleGatewayFilterChain(combined).filter(exchange);
    }

    @EventListener(RefreshRoutesResultEvent.class)
    public void onRouteRefresh(RefreshRoutesResultEvent event) {
        logger.info("Route refreshed, clearing filter cache (size=" + filterCache.size() + ")");
        filterCache.clear();
    }

    /**
     * 简单的 GatewayFilterChain 实现，替代 FilteringWebHandler 的 private DefaultGatewayFilterChain。
     */
    private static class SimpleGatewayFilterChain implements GatewayFilterChain {

        private final int index;
        private final List<GatewayFilter> filters;

        SimpleGatewayFilterChain(List<GatewayFilter> filters) {
            this.filters = filters;
            this.index = 0;
        }

        private SimpleGatewayFilterChain(SimpleGatewayFilterChain parent, int index) {
            this.filters = parent.filters;
            this.index = index;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            return Mono.defer(() -> {
                if (this.index < filters.size()) {
                    GatewayFilter filter = filters.get(this.index);
                    SimpleGatewayFilterChain chain = new SimpleGatewayFilterChain(this, this.index + 1);
                    return filter.filter(exchange, chain);
                } else {
                    return Mono.empty();
                }
            });
        }
    }

    /**
     * GlobalFilter -> GatewayFilter 适配器。
     */
    private static class GatewayFilterAdapter implements GatewayFilter {

        private final GlobalFilter delegate;

        GatewayFilterAdapter(GlobalFilter delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return this.delegate.filter(exchange, chain);
        }

        @Override
        public String toString() {
            return "GatewayFilterAdapter{delegate=" + delegate + '}';
        }
    }
}
