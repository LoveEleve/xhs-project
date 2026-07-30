# Search 热搜滑动窗口 + 防刷 — 深度技术分析

> 关联源码：`HotSearchService.java` / `SearchController.java`

---

## 业务背景

热搜榜单是搜索流量的放大器。一个关键词进入热搜榜后，点击率通常提升 300-500%。

核心挑战：
- **实时性**：热搜需要分钟级更新，不能等小时级离线计算
- **防刷**：恶意用户刷词会上热搜，污染榜单
- **衰减**：1 小时前的搜索贡献应该低于 1 分钟前的

---

## 架构

```
用户搜索 → recordSearchKeyword()
    │
    ├─ Lua 原子脚本（一次网络往返完成全部校验）
    │   ├─ 屏蔽词检查（SISMEMBER）
    │   ├─ IP 限速（INCR + EXPIRE 60s）
    │   ├─ 用户去重（SET NX EX 300s）
    │   └─ 分钟桶写入（HINCRBY）
    │
    ▼
分钟桶 Redis Hash：search:window:{yyyyMMddHHmm}
    │  TTL=2h，自动淘汰过期桶
    │
    ▼
Scheduled @fixedRate=5min → calculateHotSearch()
    │
    ├─ Pipeline 批量读取最近 60 个分钟桶
    ├─ 指数衰减计算：Score = Σ(count × e^(-0.1 × Δt))
    ├─ 过滤屏蔽词
    ├─ 排序取 Top 50
    ├─ RENAME 原子替换实时 ZSet
    └─ 快照持久化到 MySQL
```

---

## 滑动窗口设计

### 分钟桶

```
Key: myxhs:search:window:{yyyyMMddHHmm}
Type: Hash
Example: myxhs:search:window:202607301430 → { "连衣裙": 15, "穿搭": 8 }
TTL: 2 小时
```

每个分钟桶是一个独立的 Hash，key 为时间戳（精确到分钟），field 为搜索词，value 为搜索次数。

**为什么用 Hash 而不是 ZSet**：
- Hash 的 HINCRBY 天然原子自增
- 不需要 ZSet 的排序功能（排序在计算阶段做）
- Hash 存储一个 keyword→count 的映射，空间效率高于 ZSet（ZSet 的 score 会额外存储 double）

### 滑动范围

`windowMinutes = 60`：计算最近 60 分钟的数据。60 分钟之前的桶要么已过期（TTL=2h），要么不被计算。

---

## 指数衰减算法

```java
Score = Σ(count × e^(-λ × Δt))

λ = 0.1（衰减系数）
Δt = 分钟差（当前时间 - 桶时间）
```

| Δt | e^(-0.1 × Δt) | 效果 |
|---|---|---|
| 0（刚刚） | 1.0 | 权重最大 |
| 5 分钟 | 0.61 | 仍有较高权重 |
| 10 分钟 | 0.37 | 明显衰减 |
| 30 分钟 | 0.05 | 权重极低 |
| 60 分钟 | 0.002 | 几乎不计 |

**为什么是指数衰减不是线性**：
- 线性衰减：10 分钟前权重 0.83，20 分钟前 0.67——衰减太慢
- 指数衰减：10 分钟前 0.37，20 分钟前 0.14——快速淘汰旧热点

---

## 反作弊体系

### 三层防护

```lua
-- Lua 脚本：一次网络往返完成三层反作弊 + 数据写入

-- 0. 屏蔽词检查（防 TOCTOU 竞态）
if SISMEMBER blockedSet keyword then return -1

-- 1. IP 限频（每分钟最多 10 次搜索）
if INCR ipKey == 1 then EXPIRE ipKey 60
if ipCount > 10 then return 0

-- 2. 用户去重（同词 300 秒冷却）
if EXISTS userKey then return 0
SET userKey 1 EX 300

-- 3. 写入分钟桶
HINCRBY bucketKey keyword 1
EXPIRE bucketKey 7200
return 1
```

| 层 | 目标 | 粒度 | 惩罚 |
|---|---|---|---|
| 屏蔽词 | 违法违规内容 | 全局 | 完全禁止 |
| IP 限频 | 批量刷词机器人 | 单 IP | 60 秒限制 |
| 用户去重 | 重复点击 | 用户+关键词 | 300 秒冷却 |

### 为什么用 Lua

Lua 脚本的原子性消除了 **TOCTOU 竞态**：

```
❌ 非原子操作：
  if (SISMEMBER blocked, keyword) → return  // T1
  if (GET userKey) → return                  // T2
  ← 其他线程此时写入了同一个 keyword        // 竞争
  HINCRBY bucket, keyword                   // T3

✅ Lua 原子操作：
  EVALSHA → 所有检查 + 写入在 Redis 单线程中顺序执行，无竞争窗口
```

---

## 热度计算定时任务

### Pipeline 批量读取

```java
// Pipeline 一次性读取最近 60 个分钟桶（1 次网络往返）
List<Map<String, String>> allBuckets = stringRedisTemplate.executePipelined(connection -> {
    for (String key : bucketKeys) {
        connection.hashCommands().hGetAll(serializer.serialize(key));
    }
    return null;
});
```

60 个独立查询 → 1 次 Pipeline 往返，节省 59 次网络延迟。

### 原子 RENAME 替换

```java
// 先写入临时 Key → RENAME 替换（避免 DELETE+ADD 空窗期）
stringRedisTemplate.delete(HOT_REALTIME_TMP);
for (...) {
    stringRedisTemplate.opsForZSet().add(HOT_REALTIME_TMP, keyword, score);
}
stringRedisTemplate.rename(HOT_REALTIME_TMP, SEARCH_HOT_REALTIME);
```

如果不使用临时 Key，直接 `DELETE + ZADD` 覆盖，DELETE 后和 ZADD 前存在空窗期，此时查询热搜榜会得到空数据。

### 分布式锁

```java
RLock lock = redissonClient.getLock("lock:job:search:hot:calculate");
lock.tryLock(0, 600, TimeUnit.SECONDS); // 等待 0s，持有 600s
```

多实例部署时只有一个实例执行热度计算。600 秒持有时间保证即使一次计算耗时过长也不会频繁触发。

---

## 热搜榜单查询

```java
// 置顶词先展示（不占热度排名），然后按热度降序排列
// 超过 Top 50 的不展示
// 标签规则：1-3 名且分数 > 80% 最高分 → "爆"；4-10 → "热"；其余 → "新"
```

**置顶 vs 屏蔽**：
- 置顶：管理员手动将某些词固定在榜单顶部（如官方活动）
- 屏蔽：屏蔽违法违规词，从榜单移除

---

## 面试 Q&A

**Q: 一分钟内同一个人搜索同一个词 10 次，怎么算？**
A: 用户去重层会在第一次搜索时写入 `SET NX EX 300` 的 Key，后续 300 秒内同一个 userId+keyword 的请求都会被丢弃（返回 0）。同时 IP 限频层限制每分钟最多 10 次搜索（无论什么词）。

**Q: 热度是怎么计算的？**
A: 每 5 分钟 Pipeline 批量读取最近 60 分钟的分钟桶，用指数衰减 `Score = Σ(count × e^(-0.1 × Δt))` 计算每个词的总分，取 Top 50 写入 Redis ZSet。

**Q: 为什么分钟桶 TTL 是 2 小时，但只计算最近 60 分钟？**
A: 冗余设计。60 分钟外的桶不参与计算，但 TTL=2h 可以容忍定时任务偶尔延迟执行。即使定时任务延迟 30 分钟，数据还在。

**Q: 多实例部署时会不会重复计算？**
A: Redisson 分布式锁保证只有一个实例执行。锁持有 600 秒，计算通常 <1 秒，不会触发锁超时。

---

## 生产实验

已验证：
- 热搜上报 → 200 ✅
- 热搜列表 → Top 3（test/草稿/Canal），含 score/tag ✅
- 置顶/屏蔽 → 200 ✅

未验证：
- 防刷限频（需要 1 分钟内发 11 次请求）
- 热度衰减计算（需要等待定时任务执行）
- 快照持久化到 MySQL（需要 t_hot_search_snapshot 表）

---

## 发散

### 实时热搜

当前滑动窗口 5 分钟计算一次，无法秒级感知突发热点。如果对实时性要求更高，可以在分钟桶写入时同步更新一个"当前分钟"的热门词列表（ZSet INCR），这样查询端可以拿到近实时的热门词（精度到分钟）。但实现复杂度更高，当前 5 分钟足以满足业务需求。

### PC 端和移动端分开

PC 端和移动端用户的搜索习惯不同。可以维护两套分钟桶（`search:window:pc:{HHmm}` 和 `search:window:app:{HHmm}`），分开计算热度。
