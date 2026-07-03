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
            @Value("${spring.data.redis.password:Xhs@2026#Redis}") String password) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setPassword(password);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    @Qualifier("cacheStringRedisTemplate")
    public StringRedisTemplate cacheStringRedisTemplate(
            @Qualifier("cache") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
