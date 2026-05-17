package com.myxhs.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Redis Lua 脚本配置
 * <p>
 * 预加载 Lua 脚本为 Spring Bean，避免每次执行时重复加载和解析。
 * Redis 会缓存脚本的 SHA1，后续执行走 EVALSHA 而非 EVAL，性能更优。
 * </p>
 */
@Configuration
public class RedisScriptConfig {

    /**
     * 关注 Lua 脚本
     * 原子操作：ZADD 关注列表 + ZADD 粉丝列表 + INCR 关注数 + INCR 粉丝数
     */
    @Bean
    public DefaultRedisScript<Long> followScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/follow_and_count.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 取关 Lua 脚本
     * 原子操作：ZREM 关注列表 + ZREM 粉丝列表 + DECR 关注数 + DECR 粉丝数
     */
    @Bean
    public DefaultRedisScript<Long> unfollowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/unfollow_and_count.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
