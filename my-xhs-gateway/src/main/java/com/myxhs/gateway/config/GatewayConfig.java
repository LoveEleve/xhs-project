package com.myxhs.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.cloud.sentinel.datasource.converter.JsonConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Gateway 配置类
 * <p>
 * 注册 Gateway 鉴权所需的 Bean，以及跨域配置。
 * </p>
 */
@Configuration
public class GatewayConfig {

    /**
     * Sentinel Gateway 限流规则 JSON 反序列化器
     * <p>
     * Spring Cloud Alibaba Sentinel Starter 的 SentinelDataSourceHandler 在加载
     * rule-type=gw-flow 的 Nacos 数据源时，会查找名为 sentinel-json-gw-flow-converter 的 Bean。
     * 该 Bean 不会自动注册（SCA 的已知问题），需手动注册。
     * <p>
     * 作用：将 Nacos 中的 JSON 格式 Gateway 限流规则反序列化为 GatewayFlowRule 对象，
     * 并注册到 GatewayRuleManager 中生效。
     */
    @Bean("sentinel-json-gw-flow-converter")
    public Converter<String, GatewayFlowRule> sentinelGwFlowConverter(ObjectMapper objectMapper) {
        return new JsonConverter<>(objectMapper, GatewayFlowRule.class);
    }

    /**
     * Sentinel 降级规则 JSON 反序列化器
     * <p>
     * 注意：该 Bean 已由 SentinelAutoConfiguration 自动注册，此处不重复注册。
     * 如果重复注册会导致 Bean 定义冲突（spring.main.allow-bean-definition-overriding=false）。
     */

    /**
     * StringRedisTemplate（Gateway 只需要 String 操作，检查 Token 黑名单）
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 跨域配置（WebFlux 环境必须使用 reactive 包下的 CorsConfigurationSource）
     * <p>
     * 【安全】生产环境使用具体域名白名单替代通配符。
     * allowCredentials=true 时不能使用 addAllowedOrigin("*")。
     * </p>
     */
    @Bean
    public org.springframework.web.cors.reactive.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
        // 生产环境域名白名单（不再使用 * 通配符）
        config.addAllowedOriginPattern("https://myxhs.com");
        config.addAllowedOriginPattern("https://www.myxhs.com");
        config.addAllowedOriginPattern("https://m.myxhs.com");
        // 压测平台特定 IP（精简为单 IP）
        config.addAllowedOriginPattern("http://10.0.0.100:*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
