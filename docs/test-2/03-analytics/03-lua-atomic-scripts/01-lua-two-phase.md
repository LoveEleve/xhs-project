# Lua 脚本原子操作与两阶段提交

> 7 个 Lua 脚本 + `RedisScriptConfig`（7 个 `DefaultRedisScript` Bean）
> 前置阅读：analytics 模块架构 — Redis 是权威数据源，Lua 脚本保证单 Key 操作的原子性和多 Key 的隔离性

---

## 1. 为什么需要 Lua？——三次 Redis 往返 vs 一次 Lua

最简单的关注操作如果用 Redis 原生命令：

```java
// 不用 Lua 的写法：三次网络往返
Boolean exists = opsForZSet().score(followingKey, targetUserId);  // ① ZSCORE 往返 1
if (exists != null) return "已关注";
opsForZSet().add(followingKey, targetUserId, timestamp);          // ② ZADD 往返 2
opsForValue().increment(followingCountKey);                       // ③ INCR 往返 3
```

三次 Redis 命令 = 三次网络往返 ≈ 3ms（单次往返 ~1ms）。

**如果在 ② 和 ③ 之间 Redis 进程崩溃**：ZADD 成功但 INCR 未执行 → counter 少 1 → 计数不一致。三个命令不是原子的。

```lua
-- follow_self.lua：一次 EVAL 调用完成 ZSCORE + ZADD + INCR，原子执行
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then return 0 end      -- 已关注，幂等返回

redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])  -- 关注时间戳
redis.call('INCR', KEYS[2])                     -- 计数+1
return 1
```

一次 EVAL = 一次网络往返 ≈ 1ms。三个命令在 Lua 沙箱中顺序执行，**不会被其他命令打断**（Redis 单线程执行模型）。

---

## 2. 7 个 Lua 脚本全景

| 脚本 | 操作 | 原子操作 | 幂等返回 |
|------|------|---------|:--:|
| `like_atomic.lua` | SADD 正向 + SADD 反向索引 | SADD × 2 | 0=已存在 |
| `unlike_atomic.lua` | SREM 正向 + SREM 反向索引 | SREM × 2 | 1=删除成功 |
| `follow_self.lua` | ZSCORE + ZADD + INCR | 3 个命令 | 0=已关注 |
| `follow_target.lua` | ZSCORE + ZADD + INCR | 3 个命令 | 0=已关注 |
| `unfollow_self.lua` | ZSCORE + ZREM + DECR(>0) | 3 个命令 | 0=未关注 |
| `unfollow_target.lua` | ZSCORE + ZREM + DECR(>0) | 3 个命令 | 0=未关注 |
| `favorite_atomic.lua` | ZSCORE + ZADD | 2 个命令 | 0=已收藏 |

**加载方式**：`RedisScriptConfig` 预加载为 Spring Bean，用 `DefaultRedisScript` 包装：

```java
@Bean
public DefaultRedisScript<Long> followSelfScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("lua/follow_self.lua"));
    script.setResultType(Long.class);  // ← 声明返回Long，Spring自动转换
    return script;
}
```

---

## 3. 关注的两阶段问题

### 3.1 为什么 follow 必须拆成两个脚本？

一个关注操作涉及两个用户的数据：

```
关注者(10001)的数据：
  myxhs:follow:list:10001          ← follow_self.lua 操作
  myxhs:counter:user_following:10001

被关注者(2078387513547841537)的数据：
  myxhs:follow:fans:2078387513547841537   ← follow_target.lua 操作
  myxhs:counter:user_follower:2078387513547841537
```

这两个 Key 属于不同的 Redis slot，EVAL 不允许跨 slot 操作。拆成两个独立脚本是 Redis Cluster 的硬性要求。

### 3.2 失败场景与对账修复

```
follow() {
  Step A: follow_self.lua  → ✅ 成功（关注者 ZSet + counter 已更新）
  Step B: follow_target.lua → ❌ 失败（被关注者侧 Redis OOM / 连接断开）
  Step C: MySQL INSERT      → ✅ 成功
}

结果状态：
  关注者侧 counter = 1 ✅
  被关注者侧 counter = 0 ❌（少 1）
  ZSet: 关注者列表有 2078387513547841537 ✅
  ZSet: 粉丝列表缺少 10001 ❌
  MySQL: t_follow 有记录 ✅
```

**为什么不能回滚 Step A？** 因为两个脚本操作不同用户的数据——没有跨 Key 的分布式事务。Redisson 的分布式锁也无法回滚已执行的 Redis 命令。

**为什么接受这个风险？** 社交计数不是金融账本。误差由 `FollowCounterRepairJob`（每小时）对账修复——以 ZCARD 为准覆写 counter。如果需要强一致，需要引入 Seata TCC，但复杂度提升巨大。

### 3.3 Step B 失败不影响返回

```java
try {
    stringRedisTemplate.execute(followTargetScript, keys, args);
} catch (Exception e) {
    log.error("目标用户粉丝列表写入失败（对账修复）: ...", e);
    // 不抛异常 — follow 整体返回"成功"
}
```

用户体验优先：关注者侧操作已完成，用户看到"关注成功"。被关注者侧的不一致对用户不可见（粉丝数展示可能短暂少 1），下次对账自动修复。

> 对账修复机制详见 `08-counter-repair/01-reconciliation-job.md`

---

## 4. DECR 防负数——为什么需要？

```lua
-- unfollow_self.lua
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then return 0 end

redis.call('ZREM', KEYS[1], ARGV[1])
local count = redis.call('GET', KEYS[2])
if count and tonumber(count) > 0 then    -- ← 关键：防止负数
    redis.call('DECR', KEYS[2])
end
return 1
```

**什么场景下 count 已经为 0 但 ZSet 还有成员？**

```
情景：Step A（取关）已执行 → ZREM + DECR → counter=0
      但用户在 Step A 后又执行了某些操作 → ZSet 被重新加入（异常路径）
      再次 Step A → ZREM 成功（幂等）→ counter 已是 0 → DECR 变 -1
```

`> 0` 保护确保 counter 永远 ≥ 0。即使出现极端的不一致，counter 也不会变成负数——最坏结果是 0（对账修复时 ZCARD 会纠正）。

---

## 5. Redis Cluster 的 hash tag 陷阱

```lua
-- follow_self.lua 操作两个 Key
KEYS[1] = "myxhs:follow:list:10001"
KEYS[2] = "myxhs:counter:user_following:10001"
```

Redis Cluster 对每个 Key 做 `CRC16(key) % 16384` 分配到不同 slot。两个 Key 前缀不同 → 不同 slot → EVAL 报错：

```
CROSSSLOT Keys in request don't hash to the same slot
```

**正确做法**——用 hash tag `{...}` 只对 tag 内容做哈希：

```
myxhs:user:{10001}:follow:list     ← CRC16("10001") → slot X
myxhs:user:{10001}:counter          ← CRC16("10001") → 同样的 slot X
```

**当前项目为什么没暴露？** Redis Sentinel 是单主多从模式，只有一个节点处理所有 Key，不需要 slot 路由。但迁移到 Cluster 时，这条线会直接报错。

---

## 6. hasReverse 参数——一个脚本处理两种场景

```lua
-- like_atomic.lua
-- ARGV[3] = "1" → 笔记点赞 → 需要反向索引
-- ARGV[3] = "0" → 评论点赞 → 不需要反向索引
if hasReverse == '1' then
    redis.call('SADD', reverseKey, reverseMember)
end
```

**为什么要参数化而非写两个脚本？**

| 方案 | 脚本数 | SHA1 数 | 维护成本 | 变更影响 |
|------|:--:|:--:|:--:|:--:|
| 两个脚本（like_note + like_comment） | 2 | 2 | 改一个文件需同步另一个 | 高 |
| 一个脚本 + hasReverse 参数 | 1 | 1 | 改一处生效全部 | 低 |

当 `hasReverse='0'` 时，Java 侧传的 `reverseKey` 是字符串 `"noop"`——Redis 执行 `SADD noop member` 无实际影响（noop Key 无其他操作，下次淘汰时自动清理）。

---

## 7. 总结

| 设计 | 解决什么问题 |
|------|-------------|
| Lua 替代多次 Redis 命令 | 减少网络往返（3→1），保证原子执行 |
| follow 拆分为 self + target | Redis Cluster slot 限制 |
| Step B 失败不抛异常 | 用户体验优先，对账修复兜底 |
| DECR 前检查 count > 0 | 防止极端情况下 counter 变负数 |
| hasReverse 参数化 | 一个脚本处理笔记/评论两种点赞场景 |
