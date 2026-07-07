package com.myxhs.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Gateway 限流 Key 解析器配置
 * 
 * 提供多种限流维度：
 * - remoteAddrKeyResolver: 按客户端 IP 限流（默认）
 * - principalKeyResolver: 按用户 ID 限流（需要先鉴权）
 * - pathKeyResolver: 按请求路径限流
 */
@Configuration
public class RateLimiterConfig {

    /**
     * 按客户端 IP 限流（默认策略）
     * <p>
     * 优先从反向代理头（X-Forwarded-For / X-Real-IP）获取真实客户端 IP，
     * 避免在有 Nginx/负载均衡器时，remoteAddress 被识别为代理 IP。
     * </p>
     * 适用于未登录接口（注册、登录等）
     */
    @Bean
    public KeyResolver remoteAddrKeyResolver() {
        return exchange -> {
            // 优先从反向代理头获取真实 IP
            String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (ip == null || ip.isEmpty()) {
                ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
            }
            if (ip == null || ip.isEmpty()) {
                ip = exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown";
            }
            // X-Forwarded-For 可能包含多个 IP（逗号分隔），取第一个
            if (ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return Mono.just(ip);
        };
    }

    /**
     * 按用户 ID 限流
     * 适用于已登录接口，需要鉴权 Filter 先设置 X-User-Id Header
     */
    @Bean
    public KeyResolver principalKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            return Mono.just(userId != null ? userId : "anonymous");
        };
    }

    /**
     * 按请求路径限流
     * 适用于全局接口级别限流
     */
    @Bean
    public KeyResolver pathKeyResolver() {
        return exchange -> Mono.just(exchange.getRequest().getURI().getPath());
    }
}
