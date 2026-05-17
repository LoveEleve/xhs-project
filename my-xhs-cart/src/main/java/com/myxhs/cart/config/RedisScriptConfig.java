package com.myxhs.cart.config;

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
 * <p>
 * 购物车场景为什么要用 Lua 脚本？
 * 1. 加入购物车涉及 5 个 Redis 命令（HEXISTS + HLEN + HINCRBY + SADD + ZADD），
 *    非原子执行时，并发加购可能突破 50 品上限。
 * 2. 删除商品涉及 3 个结构（Hash + Set + ZSet），非原子删除可能导致数据不一致。
 * 3. Lua 脚本在 Redis 单线程中执行，天然保证原子性，且只需 1 次网络往返。
 * </p>
 */
@Configuration
public class RedisScriptConfig {

    /**
     * 加入购物车 Lua 脚本
     * 原子操作：检查上限 + HINCRBY 累加 + 截断上限 + SADD 选中 + ZADD 排序
     */
    @Bean
    public DefaultRedisScript<Long> cartAddScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/cart_add.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 删除购物车商品 Lua 脚本
     * 原子操作：三结构同时删除（Hash + Set + ZSet）
     */
    @Bean
    public DefaultRedisScript<Long> cartRemoveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/cart_remove.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
