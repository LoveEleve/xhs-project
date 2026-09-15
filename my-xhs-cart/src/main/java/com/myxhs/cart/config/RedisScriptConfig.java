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
    public DefaultRedisScript<Long> cartClearScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new org.springframework.scripting.support.ResourceScriptSource(
                new org.springframework.core.io.ClassPathResource("lua/cart_clear.lua")));
        script.setResultType(Long.class);
        return script;
    }

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

    /**
     * 全选/取消全选 Lua 脚本
     * 原子操作：HKEYS 获取所有 SKU → DEL 旧 Set → SADD 重建（取消全选时直接 DEL）
     * 解决竞态条件：并发 addToCart 在查 Keys 和重建 Set 之间插入的数据不会丢失
     */
    @Bean
    public DefaultRedisScript<Long> cartCheckAllScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/cart_check_all.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 修改购物车数量 Lua 脚本（C-02 修复）
     * 原子操作：HEXISTS 检查 + HSET 设置，替代原非原子的 hasKey+HSET
     */
    @Bean
    public DefaultRedisScript<Long> cartUpdateQuantityScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/cart_update_quantity.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 合并购物车通用 Lua 脚本（新商品+已有商品统一原子处理）
     * 原子操作：HEXISTS分支 → 已有:HGET+max()+HSET / 新增:HLEN+HSET+SADD+ZADD NX
     * 替代原 hasKey→get→put 三步非原子路径，消除并发复活/覆盖窗口
     */
    @Bean
    public DefaultRedisScript<Long> cartMergeItemScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/cart_merge_item.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 勾选/取消勾选 Lua 脚本
     * 原子操作：HEXISTS 校验 + SADD/SREM，消除 hasKey→SADD 的 TOCTOU 窗口
     */
    @Bean
    public DefaultRedisScript<Long> cartCheckItemScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/cart_check_item.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
