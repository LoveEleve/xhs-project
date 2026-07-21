# 热搜排行榜 Code Review

> 模块：16-热搜排行榜 | 服务：my-xhs-search (9011) | 审查时间：2026-05-14

---

## 📊 P8 评分表

| 维度 | 满分 | 得分 | 说明 |
|------|:----:|:----:|------|
| 架构设计 | 20 | 18 | 滑动窗口 + 指数衰减 + 推拉混合，架构清晰 |
| 并发安全 | 20 | 19 | Lua 原子操作 + 分布式锁 + RENAME 原子更新 |
| 性能优化 | 15 | 14 | Pipeline 批量获取 + ZSet O(logN) 排序 |
| 代码质量 | 15 | 14 | 注释完整、职责清晰、异常处理到位 |
| 可运维性 | 10 | 9 | 人工置顶/屏蔽、历史快照、日志完善 |
| 面试价值 | 10 | 10 | 滑动窗口/指数衰减/反作弊 面试高频 |
| 测试覆盖 | 10 | 8 | 功能测试完整，缺少压测和边界测试 |
| **总分** | **100** | **92** | **P8 水准** |

---

## 🔴 发现的问题及修复记录

### P0-1：ZSet 排行榜更新非原子性（先 DELETE 后 ADD 空窗期）

**问题描述**：
`doCalculateHotSearch()` 中先 `delete(hotKey)` 再逐条 `add()`，在 DELETE 和 ADD 之间有一个时间窗口（可能几十毫秒），此时 `getHotSearchList()` 会读到空数据。高并发场景下，每 5 分钟会有一次"热搜榜闪空"。

**修复前**：
```java
// ❌ 非原子：DELETE 和 ADD 之间有空窗期
stringRedisTemplate.delete(hotKey);
for (Map.Entry<String, Double> entry : topEntries) {
    stringRedisTemplate.opsForZSet().add(hotKey, entry.getKey(), entry.getValue());
}
```

**修复后**：
```java
// ✅ 原子更新：先写临时 Key，再 RENAME 替换（O(1) 原子操作）
stringRedisTemplate.delete(HOT_REALTIME_TMP);
for (Map.Entry<String, Double> entry : topEntries) {
    stringRedisTemplate.opsForZSet().add(HOT_REALTIME_TMP, entry.getKey(), entry.getValue());
}
stringRedisTemplate.rename(HOT_REALTIME_TMP, RedisKeyConstants.SEARCH_HOT_REALTIME);
```

**原理**：Redis `RENAME` 是 O(1) 原子操作，瞬间替换 Key 名称，读端不会看到中间状态。

---

### P0-2：Lua 脚本反作弊逻辑顺序错误（IP 超限时用户 Key 已写入）

**问题描述**：
原 Lua 脚本先 SET 用户 Key（步骤 1），再检查 IP 限频（步骤 2）。如果 IP 超限被拦截（return 0），但用户 Key 已经写入了 Redis，导致该用户在 5 分钟内即使换了 IP 也无法被正确计数。

**修复前**：
```lua
-- ❌ 先写用户 Key，再检查 IP → IP 超限时用户 Key 已写入
-- 1. 用户维度
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], '1', 'EX', ...)  -- 已写入！
-- 2. IP 维度
local ipCount = redis.call('INCR', KEYS[2])
if ipCount > N then return 0 end  -- IP 超限，但用户 Key 已存在
```

**修复后**：
```lua
-- ✅ 先检查 IP，再写用户 Key → IP 超限时不影响用户 Key
-- 1. IP 维度（先检查，不产生副作用）
local ipCount = redis.call('INCR', KEYS[2])
if ipCount > N then return 0 end
-- 2. 用户维度（IP 通过后才写入）
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], '1', 'EX', ...)
```

---

### P0-3：屏蔽词检查与 Lua 写入非原子（TOCTOU 竞态）

**问题描述**：
原代码先在 Java 层查 `isMember(BLOCKED, keyword)`，再执行 Lua 写入分钟桶。两步之间如果运营刚好屏蔽了该词，仍会写入分钟桶。这是经典的 TOCTOU（Time-of-Check to Time-of-Use）竞态。

**修复方案**：将屏蔽词检查移入 Lua 脚本，与写入操作在同一个原子操作中完成。

```lua
-- ✅ 屏蔽词检查纳入 Lua 脚本（原子性保证）
if redis.call('SISMEMBER', KEYS[4], ARGV[1]) == 1 then
    return -1  -- 被屏蔽
end
```

---

### P0-4：历史快照查询 SQL 索引失效

**问题描述**：
`WHERE DATE(snapshot_time) = ?` 对 `snapshot_time` 列使用了函数，导致 `idx_snapshot_time` 索引失效，全表扫描。

**修复前**：
```sql
-- ❌ DATE() 函数导致索引失效
WHERE DATE(snapshot_time) = ?
```

**修复后**：
```sql
-- ✅ 范围查询，索引生效
WHERE snapshot_time >= ? AND snapshot_time < ?
```

同时增加了日期格式校验，防止异常输入：
```java
LocalDate parsedDate;
try {
    parsedDate = LocalDate.parse(date, DATE_FORMAT);
} catch (DateTimeParseException e) {
    return Collections.emptyList();
}
```

---

### P1-5：分钟桶遍历 60 次 Redis 调用（性能瓶颈）

**问题描述**：
`doCalculateHotSearch()` 中 for 循环 60 次，每次调用 `HGETALL`，共 60 次 Redis 网络往返。假设 Redis RTT=1ms，仅此一步就需要 60ms。

**修复方案**：使用 Redis Pipeline 批量获取，一次网络往返完成 60 个 HGETALL。

```java
// ✅ Pipeline 批量获取（1 次网络往返替代 60 次）
List<Map<String, String>> allBuckets = stringRedisTemplate.executePipelined(
    (RedisCallback<Object>) connection -> {
        for (String key : bucketKeys) {
            connection.hashCommands().hGetAll(serializer.serialize(key));
        }
        return null;
    }
);
```

**性能提升**：60ms → 1ms（约 60 倍）

---

### P1-6：hashCode 碰撞导致误拦截

**问题描述**：
`keyword.hashCode()` 作为反作弊 Key 的一部分，Java String 的 hashCode 存在碰撞概率。不同搜索词可能 hashCode 相同，导致用户搜索 A 词后，搜索 B 词也被误拦截。

**修复方案**：使用 keyword 原文作为 Key 后缀。

```java
// ❌ hashCode 碰撞风险
String userKey = ANTISPAM_USER + userId + ":" + keyword.hashCode();

// ✅ 使用原文，无碰撞
String safeKeySuffix = userId + ":" + keyword;
String userKey = ANTISPAM_USER + safeKeySuffix;
```

**权衡**：Key 长度增加（中文 keyword 约 50 字节），但 Redis Key 的内存开销远小于误拦截的业务损失。且 TTL=5min 后自动清理。

---

## ⭐ 技术亮点

### 1. Lua 脚本四合一原子操作

将屏蔽词检查 + IP 限频 + 用户限频 + 写入分钟桶四个操作合并到一个 Lua 脚本中，保证原子性。这是生产环境中处理"先检查再操作"场景的标准做法。

### 2. RENAME 原子更新排行榜

使用 `RENAME` 替代 `DELETE + ADD`，消除了排行榜更新时的空窗期。这是 Redis 中更新有序集合的最佳实践。

### 3. 指数衰减算法

`Score = Σ(count × e^(-λ×Δt))` 符合"热度自然冷却"的物理直觉：
- 1 分钟前：权重 ≈ 0.90
- 10 分钟前：权重 ≈ 0.37
- 30 分钟前：权重 ≈ 0.05
- 60 分钟前：权重 ≈ 0.002

vs 线性衰减：到截止时间突然归零，不自然。

### 4. Pipeline 批量获取

60 个分钟桶的 HGETALL 通过 Pipeline 一次网络往返完成，性能提升约 60 倍。

### 5. 分布式安全全覆盖

- 定时任务：Redisson 分布式锁，多实例只有一个执行
- 反作弊：Lua 脚本原子操作，无竞态条件
- 排行榜更新：RENAME 原子替换

---

## 🎤 面试话术

### Q1: 热搜榜的热度怎么计算的？

> 1. "滑动窗口 + 时间衰减：Redis 分钟桶（Hash）记录每分钟每个词的搜索次数，TTL=2h 自然淘汰"
> 2. "每 5 分钟定时任务遍历最近 60 个分钟桶，用 Pipeline 一次网络往返批量获取"
> 3. "累加 `count × e^(-0.1×Δt)`，指数衰减让近期搜索权重更高，远期自然冷却"
> 4. "结果写入临时 ZSet，再 RENAME 原子替换正式 Key，避免读端看到空数据"

### Q2: 怎么防止热搜被刷？

> 1. "Lua 脚本四合一原子操作：屏蔽词检查 + IP 限频 + 用户限频 + 写入分钟桶"
> 2. "用户维度：同一用户同一词 5 分钟内只计 1 次（SET NX EX 5min）"
> 3. "IP 维度：同一 IP 每分钟搜索 < 10 次（INCR + EXPIRE 1min）"
> 4. "为什么用 Lua 不用 Java 分步操作？因为分步操作有 TOCTOU 竞态——检查通过后、写入前，另一个请求可能已经改变了状态"

### Q3: 排行榜更新时怎么保证读端不会看到空数据？

> 1. "不能用 DELETE + ADD，因为中间有空窗期"
> 2. "方案：先写临时 ZSet（:tmp），再 RENAME 原子替换正式 Key"
> 3. "RENAME 是 O(1) 原子操作，读端要么看到旧数据，要么看到新数据，不会看到空"
> 4. "这是 Redis 中更新有序集合的标准最佳实践"

### Q4: 为什么用指数衰减不用线性衰减？

> 1. "指数衰减 `e^(-λt)` 符合'热度自然冷却'的物理直觉"
> 2. "近期变化快（1 分钟前 0.9 → 10 分钟前 0.37），远期趋近于 0"
> 3. "线性衰减到截止时间突然归零，不自然，且需要人为设定截止时间"
> 4. "λ=0.1 是经验值，可根据业务调整——λ 越大衰减越快，热搜更新越频繁"

### Q5: 分钟桶遍历 60 次 Redis 调用怎么优化？

> 1. "原始方案：for 循环 60 次 HGETALL，60 次网络往返，RTT=1ms 时需要 60ms"
> 2. "优化：Redis Pipeline 批量获取，一次网络往返完成 60 个 HGETALL"
> 3. "性能提升约 60 倍（60ms → 1ms）"
> 4. "Pipeline 的原理：客户端一次性发送多个命令，服务端批量执行后一次性返回结果"

---

## 📋 修复清单

| # | 级别 | 问题 | 修复方案 | 状态 |
|---|------|------|----------|------|
| 1 | P0 | ZSet 更新非原子（空窗期） | RENAME 原子替换 | ✅ |
| 2 | P0 | Lua 反作弊顺序错误 | IP 检查前置 | ✅ |
| 3 | P0 | 屏蔽词检查 TOCTOU 竞态 | 移入 Lua 脚本 | ✅ |
| 4 | P0 | 快照查询 SQL 索引失效 | 范围查询 + 日期校验 | ✅ |
| 5 | P1 | 60 次 Redis 调用性能差 | Pipeline 批量获取 | ✅ |
| 6 | P1 | hashCode 碰撞误拦截 | 使用 keyword 原文 | ✅ |
| 7 | P2 | 管理员接口无权限校验 | 待后续统一鉴权 | ⏳ |

---

## 📁 文件清单

| 文件 | 行数 | 说明 |
|------|:----:|------|
| `HotSearchService.java` | 423 | 核心服务（滑动窗口 + 衰减 + 反作弊 + 快照） |
| `HotSearchVO.java` | 32 | 热搜词 DTO |
| `SearchController.java` | 178 | 新增 7 个热搜接口 + 搜索集成 |
| `RedisKeyConstants.java` | 155 | 新增 6 个热搜 Redis Key 常量 |
| `application.yml` | 112 | 新增热搜配置 |
| `t_hot_search_snapshot` | DDL | 热搜快照表 |
