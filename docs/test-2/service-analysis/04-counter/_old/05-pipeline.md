# Redis Pipeline 批量查询优化深度分析

> 源码：`CounterService.batchGetCounts()`（第 255-332 行）
> 验证：`02-counter-test.md` §1.3

---

## 1. 问题域：大 Feed 流的 N×RTT 瓶颈

首页 Feed 流展示 20 条笔记，每条需要展示点赞数、收藏数、评论数——共 60 个 Redis Key。

如果逐条 GET：

```
GET myxhs:counter:1:20001:1  → 返回  → 42   (RTT)
GET myxhs:counter:1:20001:2  → 返回  → 18   (RTT)
GET myxhs:counter:1:20001:3  → 返回  → 128  (RTT)
... ×60

→ 60 次网络往返，即使 Redis 处理只需微秒，网络延迟累计 60ms+
```

**Pipeline 优化**：将 60 个 GET 命令打包一次发送，Redis 顺序执行后一次返回 60 个结果。

```
一次发送 → GET×60 → 排队在 Redis 端顺序执行 → 一次返回 60 个结果
→ 1 次网络往返，延迟降至 ~1ms
```

---

## 2. Pipeline 底层实现：Lettuce 异步连接

```
客户端（CounterService）                          Redis 服务端
  │
  │ executePipelined(callback):
  │   connection.stringCommands().get(k1.getBytes())   ← 排队（未发送）
  │   connection.stringCommands().get(k2.getBytes())   ← 排队
  │   connection.stringCommands().get(k3.getBytes())   ← 排队
  │   ...
  │   return null;  ← 回调结束
  │   ====== Lettuce 自动 flush ======
  │   ───────── GET k1 ─────────────→
  │   ───────── GET k2 ─────────────→
  │   ───────── GET k3 ─────────────→
  │                                   │  顺序执行 GET×N
  │   ←──────── resp1 ───────────────
  │   ←──────── resp2 ───────────────
  │   ←──────── resp3 ───────────────
  │   结果序列化为 List<Object>
```

**Spring Data Redis 的封装**：

```java
stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (String key : redisKeys) {
        connection.stringCommands().get(key.getBytes());  // ① 命令入队
    }
    return null;  // ② 返回 null → Lettuce 不等待单个结果
});
// ③ Pipeline 关闭 → Lettuce flush → 接收所有响应 → 封装为 List<Object>
```

关键点：
- **callback 中的 `get()` 不立即发送**——只是把命令写入 Lettuce 的输出缓冲区
- **callback 返回后自动 flush**——一次性发送缓冲区中所有命令
- **返回值 `List<Object>`** 按命令入队顺序包含每个 GET 的响应（`byte[]` 或 `null`）

### 与 MGET 的底层差异

```
Pipeline (命令级拼接):
  RESP 协议: *2\r\n$3\r\nGET\r\n$22\r\nmyxhs:...\r\n  ← 命令1
           *2\r\n$3\r\nGET\r\n$22\r\nmyxhs:...\r\n  ← 命令2
  → Redis 逐命令解析执行，逐结果返回

MGET (单命令):
  RESP 协议: *61\r\n$4\r\nMGET\r\n$22\r\nmyxhs:...\r\n...  ← 60个key拼成一个参数数组
  → Redis 一次性处理，返回一个数组
```

网络效果相同（都是 1 次往返），但命令格式不同：Pipeline 是 N 个独立 GET 命令拼在一个 TCP 包，MGET 是一个命令包含 N 个参数。

---

## 3. 为什么选 Pipeline 而不是 MGET？

| 维度 | Pipeline | MGET |
|------|:---:|:---:|
| 命令数 | N 个独立 GET | 1 个 MGET |
| 网络往返 | 1 次 | 1 次 |
| 返回值处理 | `List<Object>`，每个元素是 `byte[]` 或 `null` | 数组 `[v1, v2, ..., vN]`，缺失 Key 对应 `null` |
| 可扩展性 | 可混入 EXISTS/TTL 等其他命令 | 只能 GET |
| Spring Data 支持 | `executePipelined(callback)` | `opsForValue().multiGet(keys)` |

**选择 Pipeline 的两个实际理由**：

**1. 未来可扩展性**

当前 `batchGetCounts` 只做 GET，但如果未来需要同时获取 TTL 或检查 Key 是否存在，Pipeline 可以直接混入这些命令，而 MGET 只支持 GET。

**2. 统一代码风格**

analytics 模块的 `LikeService.batchCheckLikeStatus` 和 `FollowService` 的关注列表都使用 Pipeline，counter 的 Pipeline 用法与之保持一致。

MGET 在技术上完全够用——60 个 GET 用 MGET 和 Pipeline 的延迟、吞吐、结果顺序完全相同。选择 Pipeline 更多是工程一致性考量。

---

## 4. 两阶段兜底：Pipeline + MySQL 批量查询

```
Pipeline GET (L1) → 命中 → 直接返回
                  → 未命中 → 收集 missedQueries → MySQL selectByTargets (L2)
```

**第一阶段（Pipeline）**：60 个 Key 一次发送，1 次往返。命中的 Key 直接有值，未命中的返回 `null`。

**第二阶段（MySQL 批量兜底）**：收集所有返回 `null` 的 Key，拼成一个 SQL：

```sql
-- N 个未命中的 Key → 1 次 SQL，不是 N 次
SELECT * FROM t_counter WHERE deleted = 0
  AND (target_type, target_id, count_type) IN
  ((1,20001,2), (1,20002,1), (1,20002,3), ...)
```

如果 60 个 Key 中 55 个命中 Pipeline、5 个未命中，MySQL 只用查 1 次（不是 5 次）。

**第三阶段（回填）**：MySQL 查到的值（或默认 0）写回 Redis，下次查询走 L1。

```java
stringRedisTemplate.opsForValue().set(redisKeys.get(i), String.valueOf(count));
```

### 回填的并发安全性

多个线程同时 `batchGetCounts` 查询同一组冷数据 → 都会 miss Pipeline → 都会查 MySQL → 都会 SET Redis。

```
线程A: SET key = "0"    线程B: SET key = "0"
```

`SET` 是幂等操作——写 5 次 `"0"` 结果仍是 `"0"`。不需要分布式锁，因为：
1. 值来自 MySQL `selectByTargets`——同一个 Key 在 DB 中只有一行，查出的值相同
2. DB 中不存在的 Key 默认为 0——所有线程都写 `"0"`

**潜在的 Cache Stampede**：如果 DB 中这个 Key 的值是 42，但 Pipeline 未命中时多个线程同时查询，每个线程都会写回 `"42"`。问题是 **没有缓存过期机制**——Key 被写回后就永久有效（无 TTL），后续查询全部走 L1。Stampede 的风险窗口只有第一次批量查询时存在，且窗口极短（所有并发请求几乎同时完成）。

---

## 5. 空值防护：三道防线

```java
// 防线 1：空查询列表
if (request.getQueries() == null || request.getQueries().isEmpty()) {
    return result;  // 直接返回空 Map
}

// 防线 2：Pipeline 结果逐个 null 判断
Object val = redisValues.get(i);
if (val != null) {
    redisHitCounts.put(i, Long.parseLong(val.toString()));  // 有值 → 命中
} else {
    missedQueries.add(...);  // null → 收集到 MySQL 兜底列表
}

// 防线 3：MySQL 兜底后回填
Counter counter = missedMap.get(lookupKey);
count = counter != null ? counter.getCountValue() : 0;  // DB 也无 → 默认 0
```

防线 3 保证了即使 Redis 和 MySQL 都没有某个 Key，返回的也是 `0` 而不是 null。

---

## 6. Pipeline 的失败模式

### 某条 GET 失败

Redis Pipeline 中每个命令独立执行。某条 GET 遇到 WRONGTYPE 错误（Key 是 Hash 而不是 String）：

```
GET k1 → "42"           (bytes, 正常)
GET k2 → ERROR          (异常对象)
GET k3 → "7"            (bytes, 正常)
```

Lettuce 将错误对象包装在 `List<Object>` 中，下标对应 k2 的位置。当前代码的 `redisValues.get(i)` 拿到错误对象后：

```java
Long.parseLong(val.toString())  // "ERROR ..." → NumberFormatException
```

**结果**：整次 `batchGetCounts` 调用失败——一个错误的 Key 类型导致全部结果丢失。这种"宁可全部失败也不返回部分数据"的设计是保守但正确的——返回不完整的数据比直接报错更难排查。

### Pipeline 连接超时

Lettuce 异步连接的 flush 操作有超时限制（默认在 `spring.redis.timeout` 中配置）。如果 Redis 响应过慢或网络拥塞，`executePipelined` 会抛出 `RedisCommandTimeoutException`。

此时已写入 flush buffer 的命令已发送到 Redis——Redis 会执行它们，只是客户端没收到响应。这些命令是只读的 GET，不影响数据一致性。

### 批量过大

如果请求 1000 条笔记 × 7 种计数 = 7000 个 Key，Pipeline 的 flush buffer 会很大（7000 × ~40 bytes RESP = ~280KB）。再加上 MySQL IN 子句的 7000 个元素——可能导致 SQL 过长或 `Packet too large`。

当前代码没有对批量大小做限制。在典型业务场景（首页 Feed 几十条笔记）中完全安全——但这是一个已知的扩展点。

---

## 7. 与 analytics 模块 Pipeline 的对比

| 维度 | counter `batchGetCounts` | analytics `batchCheckLikeStatus` |
|------|------|------|
| 查询类型 | GET (String value) | SISMEMBER (Set membership check) |
| Pipeline 实现 | `executePipelined(GET)` | `executePipelined(SISMEMBER)` |
| 批量 Key 数量 | 笔记数 × 计数类型（最大 ~500） | 笔记数（1 次查 N 个笔记的点赞状态） |
| N+1 MySQL 兜底 | `selectByTargets` | 无（analytics 用 Redis Set 纯存储） |
| 回填 Redis | ✅ | 无（不需要回填） |
| 结果类型 | `Map<targetType:targetId, Map<countName, count>>` | `Map<Long, Boolean>` |

analytics 模块的 `FollowService` 也在关注列表中内联使用 Pipeline（批量 ZSCORE 检查互关状态），但不是一个独立的 batch 方法——它嵌入在获取关注列表的流程中。counter 的 `batchGetCounts` 则是一个独立的公开 API，专门做批量计数查询。

两个模块的区别源于存储策略：analytics 用 Redis Set/ZSet 纯内存存储关注关系（不落 MySQL），counter 用 Redis + MySQL 两级存储计数。Pipeline 在两种场景下都是正确的优化选择——减少网络往返。

---

## 8. 性能效果

| 场景 | 逐条 GET | Pipeline | 加速 |
|------|:---:|:---:|:---:|
| 20 笔记 × 3 计数 = 60 Key | 60 RTT (~60ms) | 1 RTT (~1ms) | 60× |
| 100 笔记 × 5 计数 = 500 Key | 500 RTT (~500ms) | 1 RTT (~1ms) | 500× |

实测（§1.3）：60 Key Pipeline 调用 `rt=17ms`（含 HTTP 反序列化开销），纯 Redis Pipeline 部分 < 3ms。

---

## 关联文档

- `01-counter-module.md` — §4.3 批量查询
- `02-counter-test.md` — §1.3 批量查询测试
- `03-buffer-trigger.md` — CounterBuffer（Pipeline 的另一端：批量写入）
- `04-reconcile.md` — multiGet（对账修复中的批量读）
