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
 * 库存模块 Lua 脚本（C-008 键模型收敛：单库存键 + per-(sku,order) 记录，全部同 {skuId} tag 单 slot）：
 * 1. prededuct.lua：预扣减（终结墓碑检查 + 幂等 + 扣减 + 记账 + 索引）
 * 2. release.lua：释放库存（预扣回退 + 删除预扣记录 + 索引移除）
 * 3. confirm.lua：确认扣减（删除预扣记录 + 索引移除）
 * 4. mark_terminated.lua：订单终结墓碑（04B：原子「检查预扣记录 + 总是写墓碑」）
 * 5. refund_restore.lua：退款回补（累计幂等 + 库存 INCRBY 单脚本原子）
 * </p>
 */
@Configuration
public class RedisScriptConfig {

    /**
     * 预扣减 Lua 脚本（单键家族）
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
     * 退款回补 Lua 脚本（C-008：累计幂等 + 库存 INCRBY 单脚本原子——消除"推进了但没补"崩溃窗口）
     */
    @Bean
    public DefaultRedisScript<Long> refundRestoreScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/refund_restore.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 订单终结墓碑标记 Lua 脚本（04B：原子「检查预扣记录 + 总是写墓碑」，释放入口调用）
     */
    @Bean
    public DefaultRedisScript<Long> markTerminatedScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/mark_terminated.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
