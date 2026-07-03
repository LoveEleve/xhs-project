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
     * 关注-当前用户侧 Lua 脚本（写入关注列表 + 关注数 +1）
     * 【修复M6】拆分为 self/target，每个脚本 KEYS 属于同一用户，Cluster 兼容
     */
    @Bean
    public DefaultRedisScript<Long> followSelfScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/follow_self.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 关注-目标用户侧 Lua 脚本（写入粉丝列表 + 粉丝数 +1）
     */
    @Bean
    public DefaultRedisScript<Long> followTargetScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/follow_target.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 取关-当前用户侧 Lua 脚本（移除关注列表 + 关注数 -1，含防负数保护）
     */
    @Bean
    public DefaultRedisScript<Long> unfollowSelfScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/unfollow_self.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 取关-目标用户侧 Lua 脚本（移除粉丝列表 + 粉丝数 -1，含防负数保护）
     */
    @Bean
    public DefaultRedisScript<Long> unfollowTargetScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/unfollow_target.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 【M16】收藏原子 Lua 脚本：ZSCORE 检查 + ZADD 写入
     */
    @Bean
    public DefaultRedisScript<Long> favoriteAtomicScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/favorite_atomic.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 点赞原子 Lua 脚本
     * 原子操作：SADD 正向索引 + SADD 反向索引，防止中间状态不一致
     */
    @Bean
    public DefaultRedisScript<Long> likeAtomicScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/like_atomic.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 取消点赞原子 Lua 脚本
     * 原子操作：SREM 正向索引 + SREM 反向索引，防止中间状态不一致
     */
    @Bean
    public DefaultRedisScript<Long> unlikeAtomicScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/unlike_atomic.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
