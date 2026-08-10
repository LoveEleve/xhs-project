# 关注系统：双向 ZSet + Lua 原子操作 + Cluster 兼容

> **源码**: FollowService(482行) + FollowController(156行) + 4 Lua 脚本  
> **数据结构**: Redis ZSet 双向存储 + String 计数  
> **关键修复**: M6 Cluster 拆分、H1 计数端点、M3 X-Admin-Call、M4 unfollow @RateLimit

---

## 1. 双向数据模型

关注关系是**非对称**的——用户 A 关注 B，B 不一定关注 A。my-xhs 用两套 Redis Key 分别维护：

```
用户 A (userId=100) 关注了 B (userId=200)

A 侧 (关注者视角):
  myxhs:follow:list:{100}             ← ZSet: member=200, score=timestamp
  myxhs:counter:user_following:{100}  ← String: 关注数 = 1

B 侧 (被关注者视角):
  myxhs:follow:fans:{200}             ← ZSet: member=100, score=timestamp
  myxhs:counter:user_follower:{200}   ← String: 粉丝数 = 1

所有 Key 基于 RedisKeyConstants (PROJECT_PREFIX="myxhs:")
```

**为什么用两套 Key 而不是一套？**

- 一套 Key（如 `follow:relation:{A}`）只能查 A 关注了谁，不能查谁关注了 A
- 双向存储让"我的粉丝"和"我关注的人"都通过 ZSet 直接查询——ZSCORE O(1)、ZRANGE O(logN + K)。无需反向扫描全部用户

---

## 2. follow() 全链路

```java
// FollowService.java:70-125
public void follow(Long userId, Long targetUserId) {
    // 1. 防自关注
    if (userId.equals(targetUserId))
        throw new BizException(ResultCode.CANNOT_FOLLOW_SELF);

    long currentTime = System.currentTimeMillis();

    // 2. Step A: 关注者侧 Lua（关注列表 + 关注数 +1）
    Long selfResult = stringRedisTemplate.execute(
        followSelfScript,
        List.of(followingKey, followingCountKey),
        String.valueOf(targetUserId), String.valueOf(currentTime)
    );
    if (selfResult == null || selfResult == 0)
        throw new BizException(ResultCode.ALREADY_FOLLOWED);

    // 3. Step B: 被关注者侧 Lua（粉丝列表 + 粉丝数 +1）
    try {
        stringRedisTemplate.execute(
            followTargetScript,
            List.of(followerKey, followerCountKey),
            String.valueOf(userId), String.valueOf(currentTime)
        );
    } catch (Exception e) {
        // 目标用户侧写入失败不影响关注结果
        // 对账任务修复粉丝侧
        log.error("[关注] 目标用户粉丝列表写入失败（对账修复）", e);
    }

    // 4. 同步落库 MySQL
    followMapper.insert(follow);
}
```

### 为什么拆成两个 Lua 脚本？

Redis Cluster 中，Lua 脚本的所有 KEYS 必须落在同一 slot。`{userId}` hash tag 能保证 `social:following:{userId}` 和 `counter:{userId}:following` 在同一 slot——但 `social:follower:{targetUserId}` 和 `counter:{targetUserId}:follower` 在**不同** 的 slot（因为 hash tag 是 `{targetUserId}`）。

**拆分前**（所有 Key 在一个脚本 → Cluster 报 CROSSSLOT 错误）：
```lua
-- ❌ 不好：四个 Key 跨两个用户
KEYS = [myxhs:follow:list:{A}, myxhs:counter:user_following:{A},
        myxhs:follow:fans:{B}, myxhs:counter:user_follower:{B}]
```

**拆分后**（M6 修复）：
```lua
-- ✅ follow_self.lua:  只操作 A 的 Key
-- ✅ follow_target.lua: 只操作 B 的 Key
```

### 目标侧失败策略

Step B 发生异常时，代码**不抛异常**——仅打日志。现有对账任务 `repairCounter` 会定期扫 Redis → MySQL 修复不一致。这是一个**最终一致性**策略。

---

## 3. Lua 脚本逐行解析

### follow_self.lua — 关注者侧

```lua
-- follow_self.lua
-- KEYS[1] = myxhs:follow:list:{userId}         (ZSet: 关注列表)
-- KEYS[2] = myxhs:counter:user_following:{userId} (String: 关注数)
-- ARGV[1] = targetUserId
-- ARGV[2] = currentTime

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then return 0 end    -- 已关注，幂等

redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])  -- 写关注列表
redis.call('INCR', KEYS[2])                       -- 关注数 +1
return 1
```

### unfollow_self.lua — 取关者侧

```lua
-- 与 follow_self.lua 对称，操作变为 ZREM + DECR
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then return 0 end  -- 未关注，幂等

redis.call('ZREM', KEYS[1], ARGV[1])    -- 移除关注列表
redis.call('DECR', KEYS[2])              -- 关注数 -1
return 1
```

---

## 4. 查询体系

### 4.1 关注列表（ZSet 分页 + Pipeline 批量互关查询）

```java
public List<FollowVO> getFollowingList(Long userId, int page, int pageSize) {
    pageSize = Math.min(pageSize, MAX_PAGE_SIZE);  // 上限 50
    String key = FOLLOW_LIST + userId;
    long start = (long) (page - 1) * pageSize;
    long end = start + pageSize - 1;

    // 1. ZRevRange: 按 score（关注时间）倒序
    Set<ZSetOperations.TypedTuple<String>> tuples =
        stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);

    // 2. Pipeline 批量 ZSCORE 判断互关（避免 N+1）
    Set<Long> peerIds = tuples.stream().map(t -> Long.valueOf(t.getValue())).collect(toSet());
    Map<Long, Boolean> mutualMap = batchCheckMutual(userId, peerIds);

    // 3. 组装 VO（含 isMutual 标记）
    return tuples.stream().map(t -> buildVO(t, mutualMap)).collect(toList());
}
```

### 4.2 关注数/粉丝数（H1 修复）

```java
// FollowController.java:130-136
@GetMapping("/follower/count/{userId}")
public R<Long> getFollowerCount(@PathVariable Long userId) {
    return R.ok(followService.getFollowerCount(userId));
}
```

**修复前**：home 模块通过 counter 服务获取粉丝数——但 counter 的粉丝/关注计数可能因 MQ 延迟而不准确。

**修复后**（H1）：analytics 直接暴露自己的计数端点，home 模块通过 Feign 直调 analytics。因为 analytics 的 Redis 计数由 Lua 脚本实时更新，比 counter 服务的 MQ 异步路径更及时。

---

## 5. 补偿机制：repairCounter

```java
// FollowController.java:146-152
@PostMapping("/internal/repair-counter/{userId}")
public R<String> repairCounter(@PathVariable Long userId,
        @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
    if (!isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
    String result = followService.repairUserCounters(userId);
    return R.ok(result);
}
```

对账逻辑：
1. `ZCARD myxhs:follow:list:{userId}` → Redis 真实关注数
2. `ZCARD myxhs:follow:fans:{userId}` → Redis 真实粉丝数
3. 与 Redis 中的计数 String Key（由 Lua 维护）对比
4. 不一致 → 以 ZCARD 为准 SET 修正计数 Key
5. **注意**：只修 Redis 自身的不一致（ZCARD vs 计数 Key），不修 MySQL。MySQL 由其他对账任务兜底

---

## 6. 关注关系查询

```java
@GetMapping("/relation/{targetUserId}")
public R<Map<String, Boolean>> checkRelation(
        @RequestHeader("X-User-Id") Long userId, @PathVariable Long targetUserId) {
    boolean isFollowing = followService.isFollowing(userId, targetUserId);
    boolean isFollowBack = followService.isFollowing(targetUserId, userId);
    return R.ok(Map.of(
        "isFollowing", isFollowing,   // 我是否关注他
        "isFollowBack", isFollowBack, // 他是否关注我
        "isMutual", isFollowing && isFollowBack  // 互关
    ));
}
```

**实现**：`ZSCORE myxhs:follow:list:{userId} targetUserId`——O(1) 判断是否已关注，比 `ZRANK` 更精确（ZRANK 返回 null 时无法区分"不存在"和"已移除"）。

---

## 7. 故障场景

| 场景 | 处理 |
|------|------|
| Step A 成功, Step B 失败（目标Lua抛异常） | A 的关注列表已更新，B 的粉丝列表缺失。`repairCounter` 定期修复 |
| MySQL 写入失败 | 关注操作不抛异常。Redis 为权威数据源，对账修复 MySQL |
| 重复关注（并发） | `follow_self.lua` 中 `ZSCORE` 检查 + `ZADD` 原子执行，第一个返回 1，后续返回 0 |
| 关注数变为负数 | `unfollow_self.lua` 中 `ZSCORE` 先检查是否存在，不存在返回 0 |

---

## 8. 源码修复记录

| # | 级别 | 问题 | 修复 |
|---|:--:|------|------|
| M6 | 🔴 | 4 个 Lua Key 跨用户，Cluster 下 CROSSSLOT | 拆分为 self/target 双脚本 |
| H1 | 🔴 | home 读取到的粉丝/关注数为 0 | 加 `/follower/count` `/following/count` 端点 |
| M3 | 🔴 | repair-counter 无 X-Admin-Call | 加 `myxhs-admin-2026` 校验 |
| M4 | 🟡 | unfollow 无 @RateLimit | 加 20/60s |

---

*下一篇: 点赞系统 — Set-based 计数 + MQ 乱序处理*
