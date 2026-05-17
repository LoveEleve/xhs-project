package com.myxhs.content.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置
 * <p>
 * 提供 RedisMessageListenerContainer Bean，用于敏感词动态更新的多实例广播通知。
 * 当运营后台添加/删除敏感词时，通过 Redis PUBLISH 通知所有实例重建 Trie 树。
 * </p>
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
