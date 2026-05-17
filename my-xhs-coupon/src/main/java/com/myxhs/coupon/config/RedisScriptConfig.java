package com.myxhs.coupon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Redis Lua 脚本配置
 * <p>
 * 预加载 Lua 脚本为 Spring Bean，避免每次执行时重复加载和解析。
 * Redis 会缓存脚本的 SHA1，后续执行走 EVALSHA。
 * </p>
 */
@Configuration
public class RedisScriptConfig {

    /**
     * 原子领券 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> claimCouponScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/claim_coupon.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 退还券库存 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> returnCouponScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/return_coupon.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
