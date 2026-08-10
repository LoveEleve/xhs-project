# 收藏系统：ZSet 时间排序 + Lua 原子操作 + MQ syncSend

> **源码**: FavoriteService(182行) + FavoriteController(90行) + 1 Lua 脚本  
> **设计**: Redis ZSet score=时间戳，天然排序+分页+幂等  
> **关键修复**: H3 asyncSend→syncSend、2026-08-04 syncSend 异常传播

---

## 1. 数据模型

收藏系统只维护一个 ZSet——用户侧。不像关注系统有双向结构：

```
用户 100 收藏了笔记 200

  myxhs:favorite:set:{100}  ← ZSet: member=200, score=1712345678000 (时间戳)

收藏数: ZCARD myxhs:favorite:set:{100}
是否已收藏: ZSCORE myxhs:favorite:set:{100} 200
列表分页: ZRevRange myxhs:favorite:set:{100} start end
```

**为什么用 ZSet 而不是 Set？**

| | Set (Like) | ZSet (Favorite) |
|---|---|---|
| 需要计数 | SCARD O(1) | ZCARD O(1) |
| 需要时间排序 | ❌ SortedSet | ✅ ZRevRange |
| 需要分页 | ❌ | ✅ ZRevRangeWithScores |
| 幂等 | SADD | ZADD (同 member 更新 score) |

收藏需要按时间倒序展示（最新收藏在前），ZSet 的 score=时间戳天然满足。

---

## 2. favorite() 全链路

```java
// FavoriteService.java:55-72
public void favorite(Long userId, Long noteId) {
    String key = FAVORITE_SET + userId;
    long currentTime = System.currentTimeMillis();

    // 1. Lua 原子操作：ZSCORE 检查 + ZADD 写入
    Long result = stringRedisTemplate.execute(
        favoriteAtomicScript,
        Collections.singletonList(key),
        String.valueOf(noteId), String.valueOf(currentTime)
    );

    if (result == null || result == 0) return; // 已收藏，幂等

    // 2. MQ syncSend（失败则抛异常）
    sendFavoriteEvent(userId, noteId, "FAVORITE", currentTime);
}
```

### 发现与修复（2026-08-04）：syncSend 异常被吞

**问题**：`sendFavoriteEvent` 内部 catch 了所有异常并只打日志——导致的后果是：

1. Redis ZADD 成功
2. `rocketMQTemplate.syncSend` 抛异常（Broker 不可达）
3. `sendFavoriteEvent` catch 住→打日志→静默返回
4. `favorite()` 返回→用户看到"收藏成功"
5. **MQ 消息丢失**→Counter 服务收不到 FAVORITE 事件→收藏计数永远不准

**与 Like 系统的对比**：

| | LikeService | FavoriteService |
|---|---|---|
| MQ 失败处理 | `rollbackLikeLua()` + throw | ❌ 只打日志 |
| 用户感知 | "点赞失败，请重试" | "收藏成功"（实际计数丢失） |
| 一致性 | 强一致（回滚保证） | **不一致（计数永久偏差）** |

**修复**：`sendFavoriteEvent` 异常不再静默吞掉，而是向上传播让调用者感知。

---

## 3. Lua 脚本

```lua
-- favorite_atomic.lua
-- KEYS[1] = myxhs:favorite:set:{userId}
-- ARGV[1] = noteId, ARGV[2] = currentTime (score)
-- 返回 1=成功 0=已收藏(幂等)

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then return 0 end
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
return 1
```

**为什么比 Like 的 Lua 简单？** 收藏不需要反向索引（一个用户只看自己的收藏列表，不需要从笔记反查"谁收藏了我"）。

---

## 4. 查询体系

### 收藏列表（ZSet 倒序分页）

```java
public List<Long> getFavoriteList(Long userId, int page, int size) {
    size = Math.min(size, MAX_PAGE_SIZE);  // 上限 50
    String key = FAVORITE_SET + userId;
    long start = (long) (page - 1) * size;
    long end = start + size - 1;

    // ZRevRange: 按 score（收藏时间）倒序
    Set<String> noteIds = stringRedisTemplate.opsForZSet().reverseRange(key, start, end);
    return noteIds.stream().map(Long::valueOf).collect(toList());
}
```

### 收藏状态（ZSCORE O(1)）

```java
public boolean isFavorited(Long userId, Long noteId) {
    Double score = stringRedisTemplate.opsForZSet().score(FAVORITE_SET + userId, noteId);
    return score != null;
}
```

### 收藏总数（ZCARD O(1)）

```java
public long getFavoriteCount(Long userId) {
    Long count = stringRedisTemplate.opsForZSet().zCard(FAVORITE_SET + userId);
    return count != null ? count : 0L;
}
```

---

## 5. MQ 事件流

```
FavoriteService.favorite()
    │
    ▼
Redis ZSet ZADD ✓
    │
    ▼
MQ syncSend("SOCIAL_TOPIC:FAVORITE", FavoriteEvent)
    │
    ├── 成功 → CounterEventConsumer 消费 → 更新收藏计数
    │
    └── 失败 → throw Exception → 上层处理
```

CounterEventConsumer 收到 FAVORITE 事件后调用 `CounterService.incrementWithDedup(..., COUNT_TYPE_FAVORITE)`，将笔记收藏数 +1。

---

## 6. 比较：收藏 vs 点赞 vs 关注

| | Favorite | Like | Follow |
|---|---|---|---|
| 数据结构 | ZSet | Set | 双向 ZSet |
| 需要排序 | ✅ 按时间 | ❌ | ✅ 按时间 |
| 反向索引 | ❌ | ✅（笔记，非评论） | ✅（粉丝列表） |
| Lua 脚本 | 1 个（单 Key） | 2 个（like/unlike） | 4 个（follow/unfollow × self/target） |
| MQ 失败处理 | syncSend + 异常传播 | rollback + throw | 柔性处理（对账修复） |

---

*03-analytics 模块全部完成。下一篇：04-counter 模块。*
