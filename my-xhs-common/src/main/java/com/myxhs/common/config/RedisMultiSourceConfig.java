package com.myxhs.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cache Redis 数据源（16380, allkeys-lru）
 * <p>
 * Business Redis (16381, noeviction) 由 {@link RedisConfig} 管理。
 * 本配置只负责 Cache Redis，专用于可丢失的缓存数据。
 * </p>
 */
@Configuration
public class RedisMultiSourceConfig {

    @Bean
    @Qualifier("cache")
    public LettuceConnectionFactory cacheRedisConnectionFactory(
            @Value("${spring.data.redis.host:21.91.124.110}") String host,
            @Value("${spring.data.redis.cache.port:16380}") int port,
            @Value("${spring.data.redis.password:Xhs@2026#Redis}") String password,
            @Value("${spring.data.redis.timeout:1000}") String timeoutRaw) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setPassword(password);
        // 2026-09-20 review：命令超时与业务 Redis 一致（默认 1s），防故障时调用挂起
        long timeoutMs = 1000L;
        try { timeoutMs = Long.parseLong(timeoutRaw.replaceAll("[^0-9]", "")); } catch (Exception ignored) { }
        return new LettuceConnectionFactory(config,
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                        .commandTimeout(java.time.Duration.ofMillis(timeoutMs)).build());
    }

    @Bean
    @Qualifier("cacheStringRedisTemplate")
    public StringRedisTemplate cacheStringRedisTemplate(
            @Qualifier("cache") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
