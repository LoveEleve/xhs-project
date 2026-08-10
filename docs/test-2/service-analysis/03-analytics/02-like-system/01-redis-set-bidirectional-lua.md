# 点赞系统：Redis Set + Lua 原子操作 + MQ syncSend 保证一致性

> **源码**: LikeService(288行) + LikeController(111行) + 2 Lua 脚本  
> **设计**: Redis Set 正反向索引 + MQ syncSend/回滚  
> **关键修复**: H2 Set-based 计数、H4 评论点赞(bizType=2)

---

## 1. 数据模型

点赞系统用 Redis Set 存储关系，Set 天然幂等（SADD 重复返回 0）：

```
用户 100 点赞了笔记 200 (bizType=1):

正向索引（谁点了这个笔记）:
  myxhs:like:note:{200}       ← Set: members = {100, 101, 102, ...}
  点赞数 = SCARD(myxhs:like:note:{200})

反向索引（这个用户点了哪些笔记——仅笔记有，评论没有）:
  myxhs:like:user:{100}:note  ← Set: members = {200, 201, 202, ...}
```

**为什么正反向都用 Set？**

| 方案 | 优势 | 劣势 |
|------|------|------|
| Set (当前) | SADD 幂等、SCARD 计数、SISMEMBER 查询 | 内存占用量与点赞数线性增长 |
| delta 计数器 (旧方案) | 内存极省 | MQ 乱序导致计数虚增 |
| ZSet (收藏方案) | 时间排序 | 计数不如 SCARD 直观 |

当前选 Set 是因为 MQ 乱序问题无法用 delta 解决；Set 天然无序，SADD/SREM 到达顺序不影响 SCARD 结果。

---

## 2. like() 全链路

```java
// LikeService.java:67-98
public void like(Long userId, LikeRequest request) {
    String likeKey = buildLikeKey(request.getBizType(), request.getBizId());
    boolean isNoteType = request.getBizType() == BIZ_TYPE_NOTE;
    String userLikeKey = isNoteType ? LIKE_SET + "user:" + userId + ":note" : "noop";

    // 1. Lua 原子操作：正反向索引 SADD
    Long added = stringRedisTemplate.execute(
        likeAtomicScript,
        List.of(likeKey, userLikeKey),
        String.valueOf(userId), String.valueOf(request.getBizId()),
        isNoteType ? "1" : "0"
    );

    if (added == null || added == 0) return; // 已点赞，幂等

    // 2. MQ syncSend（失败 → 回滚 Redis）
    if (!sendLikeEventSync(userId, bizType, bizId, "LIKE")) {
        rollbackLikeLua(...);  // 回滚 Redis
        throw new BizException(INTERNAL_ERROR, "点赞失败，请重试");
    }
}
```

### 为什么 MQ 失败后要回滚 Redis？

对比笔记发布（afterCommit 模式）——笔记发布时 MQ 失败不影响用户，因为笔记已经在 DB 中。但点赞不同：

```
笔记发布：写入 DB（可靠） → 发 MQ（失败不影响 DB 数据）
点赞：   写入 Redis（内存） → 发 MQ（失败导致 Redis=已点，DB/Counter=未点）
```

点赞没有 MySQL 级别的持久化作为"安全网"——Redis 本身就是权威数据源。如果 MQ 失败，Redis 有点赞关系但 Counter 服务收不到 LIKE 事件，计数永远不准。所以必须回滚。

---

## 3. Lua 脚本：正反向索引原子操作

```lua
-- like_atomic.lua
-- KEYS[1] = 正向 Key (myxhs:like:note:{noteId})
-- KEYS[2] = 反向 Key (myxhs:like:user:{userId}:note 或 "noop")
-- ARGV[1] = member (userId)
-- ARGV[2] = reverseMember (noteId)
-- ARGV[3] = hasReverse ("1"=需要, "0"=不需要)

local forwardKey = KEYS[1]
local reverseKey = KEYS[2]

-- 1. 正向索引 SADD
local added = redis.call('SADD', forwardKey, ARGV[1])
if added == 0 then return 0 end  -- 已点赞，幂等

-- 2. 反向索引 SADD（仅笔记有）
if ARGV[3] == '1' then
    redis.call('SADD', reverseKey, ARGV[2])
end
return 1
```

### 为什么评论没有反向索引？

笔记的用户反向索引用于：用户主页展示"我点赞过的笔记"。评论不需要——用户不需要看到"我点赞过的评论"列表。不建反向索引节省 Redis 内存。

---

## 4. 批量查询：Pipeline SISMEMBER

```java
// LikeService.java:164-190
public Map<Long, Boolean> batchCheckLikeStatus(Long userId, int bizType, List<Long> bizIds) {
    if (bizIds == null || bizIds.isEmpty()) return Collections.emptyMap();
    if (bizIds.size() > MAX_BATCH_SIZE) throw new BizException("批量查询最多100个");

    // Pipeline 批量 SISMEMBER（N次命令 → 1次往返）
    List<Object> results = stringRedisTemplate.executePipelined(
        (RedisCallback<Object>) connection -> {
            for (Long bizId : bizIds) {
                String key = buildLikeKey(bizType, bizId);
                connection.setCommands().sIsMember(key.getBytes(), String.valueOf(userId).getBytes());
            }
            return null;
        });

    // 组装结果
    Map<Long, Boolean> result = new LinkedHashMap<>();
    for (int i = 0; i < bizIds.size(); i++) {
        result.put(bizIds.get(i), Boolean.TRUE.equals(results.get(i)));
    }
    return result;
}
```

### 应用场景

首页 Feed 流中，每条笔记需要展示"是否已点赞"状态。如果不用 Pipeline 批量查询，20 条笔记 = 20 次独立的 Redis `SISMEMBER` 调用 = 20 次网络往返。Pipeline 将 20 次合并为 1 次。

---

## 5. bizType 区分：笔记 vs 评论

```java
// LikeService.java:48-50
private static final int BIZ_TYPE_NOTE = 1;
private static final int BIZ_TYPE_COMMENT = 2;
```

| bizType | 正向 Key | 反向 Key | 谁消费 LIKE 事件 |
|:--:|------|------|------|
| 1 (笔记) | `myxhs:like:note:{noteId}` | `myxhs:like:user:{userId}:note` | CounterEventConsumer (Counter 模块) |
| 2 (评论) | `myxhs:like:comment:{commentId}` | 无 | CounterEventConsumer (Counter 模块, H4修复) |

**H4 修复前**：CounterEventConsumer 只处理 `bizType=1`，评论点赞的计数永远为 0。

---

## 6. MQ 事件流

```
LikeService.like()
    │
    ▼
Redis Set SADD ✓
    │
    ▼
MQ syncSend("SOCIAL_TOPIC:LIKE", LikeEvent{bizType, bizId, userId})
    │
    ├── 成功 → 返回 200
    │
    └── 失败 → unlikeAtomicScript 回滚 Redis → throw BizException
         │
         ▼
    用户看到 "点赞失败，请重试"
```

**为什么 `syncSend` 而不是 `asyncSend`？**

必须等 Broker 确认成功才能返回 200——否则用户看到"点赞成功"但 Redis 已回滚（或不回滚但 counter 没收到的尴尬状态）。

---

## 7. 故障场景

| 场景 | 处理 |
|------|------|
| 重复点赞 | SADD 返回 0 → 幂等返回（不报错） |
| MQ syncSend 失败 | 回滚 Redis → 抛异常 → 用户重试 |
| 回滚也失败 | 日志告警 → Redis 数据正确（SADD已成功）但 MQ/Counter 信息缺失 |
| 取消未点赞的内容 | SREM 返回 0 → 幂等返回 |

---

*下一篇: 收藏系统 — ZSet 时间排序*
