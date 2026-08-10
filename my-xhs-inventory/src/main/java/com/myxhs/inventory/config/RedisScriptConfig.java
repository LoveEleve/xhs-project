package com.myxhs.inventory.config;

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
 * 库存模块 3 个 Lua 脚本：
 * 1. prededuct.lua：分桶预扣减（检查库存 + 路由桶 + 桶间均衡 + 写预扣记录）
 * 2. release.lua：释放库存（预扣回退 + 删除预扣记录）
 * 3. confirm.lua：确认扣减（删除预扣记录）
 * </p>
 */
@Configuration
public class RedisScriptConfig {

    /**
     * 分桶预扣减 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> preDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/prededuct.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 释放库存 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> releaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/release.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 确认扣减 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> confirmScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/confirm.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 分桶完整性对账 Lua 脚本（原子求和+对比设置，替代 Java 非原子 GET 循环+SET）
     */
    @Bean
    public DefaultRedisScript<Long> reconcileBucketsScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/reconcile_buckets.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
