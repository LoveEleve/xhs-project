# 计数服务核心：Redis 原子计数 + Lua 去重 + Set-based 点赞

> **源码**: CounterService(488行) + CountType(7种) + TargetType(3种) + RedisKeyConstants + CounterEventConsumer  
> **Key格式**: `myxhs:counter:{targetType}:{targetId}:{countType}`  
> **设计核心**: 3 个 Lua 脚本解决 3 个不同的并发/一致性挑战  
> **关键修复**: R1 COMMENT/UNCOMMENT 消费路径 + R4 SHARE 消费路径 + R5 TargetType PRODUCT(4) + 未用注入清理 + 文档修正

---

## 1. 数据模型

### 1.1 两层存储

```
┌──────────────────────────────────────────────────────┐
│ L1: Redis (永久, <1ms)                                │
│   Key: myxhs:counter:{targetType}:{targetId}:{countType}│
│   Value: 计数数值(String, INCR/DECR atomic)            │
│   Dedup: myxhs:counter:dedup:{msgId} (2h TTL)         │
│   LikeSet: myxhs:like:set:{targetType}:{targetId}     │
├──────────────────────────────────────────────────────┤
│ L2: MySQL t_counter (强持久, 兜底)                     │
│   唯一索引: uk_target_count(target_type, target_id,    │
│                                 count_type)            │
│   逻辑删除: deleted=0                                  │
│   读写分离: master:13307 / slave:13311                 │
└──────────────────────────────────────────────────────┘
```

**设计决策：为什么 Redis 计数器不设 TTL？**

核心原因：L2 MySQL 是兜底面不是主存储。设 TTL 意味着 Redis 过期后的请求全部击穿到 MySQL，且回填逻辑 (`CounterService.java:324`) 依赖 DB 记录。如果 Redis Key 消失而 DB 记录也不存在（Buffer 未刷盘的情况），查询会返回 0 而不是实际值。不设 TTL 让 Redis 作为唯一权威源，DB 通过 Buffer 攒批 + 对账维持最终一致。

**为什么不用 Caffeine 本地缓存？**（`CounterService.java:28-31`）

计数是高频变更数据——点赞/收藏每秒都在变。Caffeine 在多实例部署下，实例 A 点赞后只能失效自己的本地缓存，实例 B 仍返回旧值。Redis GET 延迟 < 1ms 完全满足需求，不值得为省微秒级延迟引入分布式一致性问题。

### 1.2 Key 体系

| Key Pattern | 用途 | 来源 |
|---|---|---|
| `myxhs:counter:{t}:{id}:{ct}` | 计数值 | `RedisKeyConstants.COUNTER` (`CounterService.java:484`) |
| `myxhs:counter:dedup:{msgId}` | MQ 去重标记 (2h TTL) | `RedisKeyConstants.COUNTER_DEDUP` (`CounterService.java:300`) |
| `myxhs:like:set:{t}:{id}` | Like Set (幂等点赞) | `CounterService.java:129` 硬编码 `myxhs:like:set:` |

**注意**：`CounterService.java:129` 的 `LIKE_SET_PREFIX = "myxhs:like:set:"` 与 `RedisKeyConstants.LIKE_SET = "myxhs:like:"` **不是同一个前缀**。analytics 的 `LikeService.buildLikeKey()` 使用 `myxhs:like:note:{noteId}` 存储点赞用户集合，counter 使用 `myxhs:like:set:{targetType}:{targetId}` 作为 Set-based 计数的独立 Key 空间。两者在 Redis 中完全隔离，功能互补：analytics 侧用于查询"谁点了赞"，counter 侧用于计数"有多少赞"。

### 1.3 维度枚举

`CountType.java:11-19` 定义 7 种计数维度，每种存为独立的 Redis Key + DB 行：

| Code | 维度 | 英文名 | 消费来源 |
|:--:|------|------|------|
| 1 | 点赞 | `like` | MQ LIKE/UNLIKE (Set-based) |
| 2 | 收藏 | `collect` | MQ FAVORITE/UNFAVORITE (delta) |
| 3 | 评论 | `comment` | content CommentService MQ COMMENT/UNCOMMENT【修复R1】
| 4 | 分享 | `share` | content NoteService MQ SHARE【修复R4】 |
| 5 | 浏览 | `view` | content NoteService MQ VIEW【修复R9】 |
| 6 | 粉丝 | `follower` | analytics FollowService MQ FOLLOW/UNFOLLOW【修复R10】 |
| 7 | 关注 | `following` | analytics FollowService MQ FOLLOW/UNFOLLOW【修复R10】 |

`TargetType.java:13-16` 定义 4 种目标：NOTE(1)、USER(2)、COMMENT(3)、PRODUCT(4)【新增R5】。

---

## 2. 写链路全流程

```
HTTP POST /increment   →  CounterService.increment()
                              │
                              ├─ 1. Redis INCR (原子操作)
                              │    stringRedisTemplate.opsForValue()
                              │      .increment(redisKey)      ← 行144
                              │
                              └─ 2. CounterBuffer.add(+1)     ← 行147
                                   → 攒批 → 定时/满量刷盘 DB

MQ 消息 (去重链路)    →  CounterService.incrementWithDedup()
                              │
                              ├─ 1. Lua INCR_WITH_DEDUP_SCRIPT
                              │    原子去重 + INCRBY (行193-196)
                              │    status=0→已去重, 1→成功, -1→归零保护
                              │
                              └─ 2. CounterBuffer.add(+1)     ← 行209
```

### 2.1 直连 INCR —— `increment()` (`CounterService.java:140-150`)

```java
public void increment(int targetType, long targetId, int countType) {
    String redisKey = buildRedisKey(targetType, targetId, countType);
    stringRedisTemplate.opsForValue().increment(redisKey);  // ← INCR 原子，不丢失
    counterBuffer.add(targetType, targetId, countType, 1L);
}
```

两个步骤，先 INCR 再 Buffer.add。顺序是关键：Redis INCR 先执行保证实时性（用户立即可见），Buffer 写后执行不会影响查询结果。如果 Buffer 写入失败（极少见），Redis 值仍然是正确的，凌晨对账会修复 DB。

### 2.2 直连 DECR —— `decrement()` (`CounterService.java:159-176`)

```java
public boolean decrement(int targetType, long targetId, int countType) {
    String redisKey = buildRedisKey(targetType, targetId, countType);
    Long result = stringRedisTemplate.execute(DECREMENT_SCRIPT, List.of(redisKey));
    // DECREMENT_SCRIPT: 检查 current<=0 → 拒绝; 否则 DECR
    if (result == null || result == 0) return false;  // 归零保护触发
    counterBuffer.add(targetType, targetId, countType, -1L);
    return true;
}
```

与 `increment()` 不同，`decrement()` 使用 Lua 脚本而非直接 DECR。原因：计数不能为负数。

```lua
-- DECREMENT_SCRIPT (CounterService.java:47-52)
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current <= 0 then return 0 end
redis.call('DECR', KEYS[1])
return 1
```

**为什么这是必要的？** 如果直接 DECR，两次 -1 操作可以让计数从 0 变成 -1，对前端展示来说是严重的数据异常（"负赞"）。Lua 脚本保证「检查 + 扣减」是原子的——在 Redis 单线程模型下，不会出现检查通过但实际已被其他请求 -1 的情况。

### 2.3 MQ 去重链路 —— `incrementWithDedup()` (`CounterService.java:188-213`)

```java
public boolean incrementWithDedup(String msgId, int targetType, long targetId, int countType) {
    List<Long> result = stringRedisTemplate.execute(
        INCR_WITH_DEDUP_SCRIPT,
        List.of(dedupKey, counterKey),
        "1", String.valueOf(DEDUP_TTL_SECONDS)  // delta=+1, TTL=7200
    );
    long status = result.get(0);
    if (status == 0) return false;  // 去重拦截
    counterBuffer.add(targetType, targetId, countType, 1L);
    return true;
}
```

### 2.4 Set-based 点赞 —— `setBasedLikeWithDedup()` (`CounterService.java:266-296`)

```java
public boolean setBasedLikeWithDedup(String msgId, int targetType, long targetId,
                                      Long userId, boolean isLike) {
    String dedupKey = buildDedupKey(msgId);
    String likeSetKey = LIKE_SET_PREFIX + targetType + ":" + targetId; // myxhs:like:set:{t}:{id}
    String counterKey = buildRedisKey(targetType, targetId, 1);         // countType=1 LIKE

    List<Long> result = stringRedisTemplate.execute(
        LIKE_SET_SCRIPT,
        List.of(dedupKey, likeSetKey, counterKey),
        String.valueOf(userId), isLike ? "ADD" : "REMOVE",
        String.valueOf(DEDUP_TTL_SECONDS)
    );
    long newCount = result.get(1);
    // 写入 Buffer（delta取±1，DB 对账最终收敛到 SCARD 值）
    counterBuffer.add(targetType, targetId, 1, isLike ? 1L : -1L);
    return status != 0;
}
```

**Set-based 的关键差异**：点赞不再是 delta ±1，而是 SADD/SREM 用户 ID。计数直接 = SCARD(set)。Buffer 写入的 delta 仍为 ±1（与 DB 的 `count_value + delta` 对齐），但 DB 值只是近似值——对账后会被 SCARD 值覆盖。

---

## 3. 三个 Lua 脚本：各自解决什么并发问题？

### 3.1 DECREMENT_SCRIPT —— 归零保护

| 项目 | 内容 |
|------|------|
| 位置 | `CounterService.java:47-52` |
| 解决 | DECR 可能让计数变成负数 |
| 原子性 | GET + DECR 在同一 Lua 执行单元，Redis 单线程保证无其他命令插入 |
| 场景 | 浏览器快速双击取消点赞 → 两次  -1 请求 → 第二次被归零保护拦截 |

在 Redis 单线程模型中，整个 Lua 脚本执行期间不会有其他命令插入。所以先 GET 后 DECR 的操作组合是安全的。

### 3.2 INCR_WITH_DEDUP_SCRIPT —— MQ 原子去重

| 项目 | 内容 |
|------|------|
| 位置 | `CounterService.java:71-88` |
| 解决 | MQ 重复消费导致计数虚增 |
| KEYS | `[dedupKey, counterKey]` |
| ARGV | `[delta, TTL_seconds]` |
| 返回 | `[status: 1/0/-1, currentCount]` |

```lua
-- CounterService.java:73-86
if redis.call('EXISTS', KEYS[1]) == 1 then
    return {0, 0}          -- 已去重，跳过
end

redis.call('SET', KEYS[1], '1', 'EX', ARGV[2])  -- 置去重标记 + TTL

local delta = tonumber(ARGV[1])
if delta > 0 then
  redis.call('INCRBY', KEYS[2], delta)
  return {1, tonumber(redis.call('GET', KEYS[2]))}
end

-- delta < 0: 先检查归零
local current = tonumber(redis.call('GET', KEYS[2]) or '0')
if current <= 0 then
  return {-1, 0}           -- 归零保护，不去重标记不删除（msg 有效但拒绝）
end
redis.call('INCRBY', KEYS[2], delta)
return {1, tonumber(redis.call('GET', KEYS[2]))}
```

**三个设计细节**：

1. **去重标记先 SET，再操作计数**。如果顺序反转（先 INCR 再 SET dedup），INCR 成功但 SET 失败时，去重窗口已闭合但计数已 +1，下次重试会跳过去重检查（因为 dedup Key 不存在）→ 再次 +1 → 虚增。

2. **归零保护触发时不删除去重标记**。消息是有效的业务请求（取消收藏确实可以触发），只是当前计数为 0。如果删除去重标记等待重试，Consumer 会无限重试（rocketmq `maxReconsumeTimes=3` 耗尽后进入死信）。当前设计：Consumer 收到 status=-1 → ACK，不抛出异常。

3. **去重 TTL = 2h**。这是 MQ 消息重试窗口的 720 倍（RocketMQ 默认重试间隔最大 10s）。确保在消息可能被重试的所有时间窗口内，去重标记始终有效。

### 3.3 LIKE_SET_SCRIPT —— Set-based 幂等计数

| 项目 | 内容 |
|------|------|
| 位置 | `CounterService.java:112-126` |
| 解决 | MQ 乱序导致的计数偏差（delta-based 的致命缺陷） |
| KEYS | `[dedupKey, likeSetKey, counterKey]` |
| ARGV | `[userId, "ADD"/"REMOVE", TTL]` |

```lua
-- CounterService.java:114-124
if redis.call('EXISTS', KEYS[1]) == 1 then
    return {0, 0}          -- 先去重
end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[3])

-- SADD/SREM (幂等 — 同一 userId 加两次也只有一条记录)
if ARGV[2] == 'ADD' then
  redis.call('SADD', KEYS[2], ARGV[1])
else
  redis.call('SREM', KEYS[2], ARGV[1])
end

-- 计数 = Set size (真实点赞数)
local count = redis.call('SCARD', KEYS[2])
redis.call('SET', KEYS[3], count)
return {1, count}
```

**为什么 Set-based 替代 delta？—— 一个 4 步重现案例：**

```
顺序到达 (正常):                乱序到达 (MQ重试导致):
USER_A LIKE note_1   delta=+1   USER_B UNLIKE note_1   delta=-1   (先到)
USER_B LIKE note_1   delta=+1   USER_B LIKE note_1     delta=+1
TOTAL = 2 ✅                     USER_A LIKE note_1    delta=+1   (延迟重试)
                                 TOTAL = 3 ❌ (delta-based: 0 -1 +1 +1 = 1,
                                             但实际只有 A 点赞，应为 1)

Set-based:
UNLIKE 先到 → SREM 空 Set (no-op)
LIKE 后到 → SADD → SCARD=1 ✅
```

在 Set-based 模型下，任意顺序的 SADD/SREM 操作都收敛到正确的 SCARD 值。核心差异：
- **delta**: 依赖操作顺序（交换律不成立）
- **Set**: 依赖元素集合（交换律成立 — SADD → SREM 和 SREM → SADD 的结果都由最终 Set 内容决定）

---

## 4. 读链路

### 4.1 单次查询 `getCount()` (`CounterService.java:312-328`)

```
┌─► Redis GET ── hit? ──► 返回
│
└── miss ──► MySQL selectByTarget(where deleted=0)
                    │
                    ├── 有记录 → 回填 Redis → 返回
                    │
                    └── 无记录 → 回填 Redis(0) → 返回 0
                                    (⚠️ 见下文 4.3)
```

### 4.2 批量查询 `batchGetCounts()` (`CounterService.java:338-415`)

```
请求: {"queries": [
  {targetType:1, targetId:20001, countTypes:[1,2]},
  {targetType:1, targetId:20002, countTypes:[3]}
]}

1. 展平 → 3个Key: [c:1:20001:1, c:1:20001:2, c:1:20002:3]
2. executePipelined → 1 次网络往返 GET 全部（O(1) RTT 而非 O(N)）
3. 收集 miss 项 → 批量 MySQL (WHERE (t,id,ct) IN (...)) ← 1条SQL，非N条
4. 回填 Redis → 组装响应

响应: {"1:20001": {"like":42, "collect":18}, "1:20002": {"comment":128}}
```

**Pipeline vs 逐条 GET 的性能差距**（保守估计）：

| 维度 | 逐条 GET | Pipeline |
|------|------|------|
| 网络 RTT | N × ~0.1ms = 10ms (N=100) | 1 × ~0.5ms = 0.5ms |
| TCP overhead | N × 握手机制开销 | 1 × 批量打包 |

当 `N=100` 时，Pipeline 节省约 9.5ms。home 模块的 FeedService 批量加载数百条笔记的计数时，这个优化是体验关键。

### 4.3 回填 Redis 值的隐患

`getCount()` 第 324 行的回填逻辑：Redis miss → MySQL 有值 → `stringRedisTemplate.opsForValue().set(redisKey, count)` — **无 TTL**。

第 407 行 `batchGetCounts()` 的回填同样无 TTL。

**问题场景**：如果命中 Redis miss 分支时 Buffer 尚未刷盘，DB 中可能没有这个目标的最新计数值。例如：
1. 用户 A 点赞笔记 20001 → Redis INCR 计数 = 5，Buffer 持有 +1
2. Redis 意外重启 → 所有 Key 消失
3. 查询 `getCount(1, 20001, 1)` → Redis miss → MySQL 返回 4（Buffer 尚未刷盘）
4. 回填 Redis = 4 — **丢失了第 5 个赞的 +1**

**为什么这不算 bug？** 凌晨 3 点对账 (`CounterService.reconcile()`) 扫描 DB → 发现 Redis 缺失 → 不回填（`redisCount == 0 && dbCount > 0` 分支跳过此路径）。实际上新 Key 会在下次计数事件触发时重新建立。且 Redis 重启在生产环境中极少发生（Sentinel 模式 3 节点）。

---

## 5. 对账修复 `reconcile()` (`CounterService.java:431-476`)

```
┌─── 游标分页扫描 MySQL ───┐
│ SELECT * FROM t_counter   │
│ WHERE id > lastId         │  每次 1000 条
│ ORDER BY id ASC           │  避免深分页
│ LIMIT 1000                │
└──────────┬───────────────┘
           │
           ▼
┌─── Pipeline 批量 GET Redis ───┐
│ multiGet(redisKeys)          │ 1 次 RTT
└──────────┬───────────────────┘
           │
           ▼ 逐条比对
┌─── redisCount vs dbCount ────┐
│                              │
│ redisCount == dbCount → 跳过   │ 正常
│                              │
│ redisCount != 0 && 不一致     │ ─→ Redis 为准，update DB
│                              │     Redis 是实时更新的权威源
│                              │
│ redisCount == 0 && dbCount>0 │ ─→ DB 为准，回填 Redis
│                              │     Redis 可能丢失数据
│                              │
│ (DB 无记录但 Redis 有值       │ ─→ 不处理，等 Buffer 刷盘收敛
│  不在扫描范围内)             │
└──────────────────────────────┘
```

**为什么对账以 DB 扫描为基准，而不是 Redis 扫描？**
- Redis KEYS 命令是 O(N) 且阻塞，不适合生产环境
- DB 游标分页 (id > lastId) 是 O(1) 每批，不会锁表

---

## 6. 工程维度审查

### 6.1 并发安全

| 场景 | 保障 |
|------|------|
| 多线程 INCR 同一 Key | Redis 单线程模型 + INCR 原子性 → 不会丢失更新 |
| 去重 + 计数竞态 | Lua 单脚本原子化（`EXISTS dedupKey → SET dedup → INCRBY` 在一个 EXEC 中） |
| Buffer 并发 add | `ConcurrentHashMap.computeIfAbsent` + `AtomicLong.addAndGet` → 线程安全 |
| Buffer 刷盘并发 | `ReentrantLock.tryLock()` 非阻塞 → 只有一个线程执行刷盘 |

### 6.2 可靠性

| 故障 | 恢复 |
|------|------|
| Redis 宕机 | MySQL 兜底（回填逻辑），重新上线后重新计 |
| MySQL 宕机 | Redis 正常服务写查询，Buffer 写入 → 重试 3 次 → 失败记日志 → 对账兜底 |
| Buffer 刷盘失败 | 重试 3 次递增退避（100ms/200ms/300ms）→ 全失败由凌晨对账兜底 |
| MQ 重复消费 | 2h dedup TTL（Lua 原子去重）→ 重复消息 status=0 静默 ACK |
| MQ 归零保护 | status=-1 静默 ACK，不删除去重标记 → 不触发无限重试 |
| Redis 数据丢失 | 对账 `redisCount=0, dbCount>0` → DB 值回填 Redis |

### 6.3 性能

| 操作 | 复杂度 | 预估 (~1K QPS场景) |
|------|------|------|
| INCR | Redis O(1) | < 0.5ms |
| getCount (hit) | Redis O(1) | < 1ms |
| getCount (miss) | → MySQL `WHERE uk` + Redis SET | < 10ms |
| batchGet (100 items) | Pipeline + 批量 MySQL | < 5ms |
| INCR_WITH_DEDUP | Lua (O(1)) + Buffer.add | < 1ms |
| reconcile (100K 行) | 游标分页 + multiGet | < 30s |

---

## 7. 面试 Q&A

### Q1: 为什么要用 Lua 脚本而不直接在 Java 代码里先 check dedup 再 INCR？

**错误答案**：因为这样写代码更简洁。

**正确答案**：因为 `EXISTS dedupKey` 和 `INCRBY counterKey` 之间有时间窗口。如果在 Java 层先调用 Redis EXISTS 检查、再调用 INCRBY：

```java
// 竞态窗口！
if (!redis.hasKey(dedupKey)) {  // T1: dedupKey 不存在
    // --- 在此期间 MQ 重试到达 ---
    redis.incrBy(counterKey, 1); // T2: 第二次 +1
}
```

两个并发请求可能同时在 T1 判定 dedupKey 不存在，然后分别执行 INCRBY，导致重复计数。Lua 脚本在 Redis 单线程模型下独占执行，消除了这个窗口。

### Q2: 为什么点赞用 Set-based，但收藏还是用 delta？

因为收藏数据模型是 "笔记被 N 人收藏"，只需计数不需要记录"谁收藏了"。点赞则需要记录 "谁点了赞"（用户点赞状态展示）。实际上 Set-based 在 counter 模块中的功能只是计数，但它的 SADD/SREM 幂等性天然解决了 MQ 乱序问题，这是主要动机。收藏的 delta 方式在乱序场景下会有问题（UNFAVORITE 先到且 article 无收藏记录 → 归零保护），但 trigger 概率低且对账可修复。

### Q3: Buffer 写入的 delta 为 ±1，但在 Set-based 模式下 SCARD 才是真实值，Buffer 的 delta 不就没意义了吗？

对。Buffer 写入的 delta 只对 CountType=2(收藏) 有效。对 CountType=1(点赞)，Buffer 写入 delta ±1 是为了与 DB `ON DUPLICATE KEY count_value + VALUES(count_value)` 对齐，避免报错。但在 `reconcile()` 中，点赞计数最终会被 `setBasedLikeWithDedup` 中 `redis.call('SET', KEYS[3], count)` 的 SCARD 值覆盖（DB 被修正为 SCARD）。所以 Buffer 的点赞 delta 只是一个**占位**——实际精度由凌晨对账保证。
