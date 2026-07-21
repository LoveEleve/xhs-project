# 13-消息推送与通知 CODE-REVIEW

## 一、P8 评分表

| 维度 | 评分(1-10) | 说明 |
|------|:----------:|------|
| **架构设计** | 9 | SSE 实时推送 + MQ 异步解耦 + Redis 计数 + MySQL 持久化，四层架构清晰 |
| **并发安全** | 9 | Lua 原子操作（聚合窗口锁、安全 DECR、按类型重置）、ConcurrentHashMap.put 原子替换 |
| **分布式考量** | 8.5 | 定时任务 Redisson 分布式锁、Redis 记录 userId→serverId 映射、心跳 Pipeline 批量续期 |
| **幂等设计** | 8.5 | MQ 消费 Redis msgId 去重 + 聚合窗口锁双重保障，消费成功后才写去重 Key |
| **数据一致性** | 9 | 未读计数 Redis vs MySQL 对账兜底、Lua 防负数、分批对账避免 OOM |
| **安全性** | 9 | SSE 两步法 Ticket（POST 获取 → GET 建连），避免 Token 泄露到 URL |
| **代码质量** | 8.5 | 枚举消除重复、模板本地缓存、链式 API、完善的 Javadoc |
| **可运维性** | 8 | 心跳保活、连接清理回调、在线计数接口、对账日志 |
| **综合评分** | **8.7** | 对标大厂 P7+ ~ P8 水平 |

## 二、发现的问题及修复记录

### 🔴 严重问题

#### 问题 1：聚合器 SETNX + SET 非原子（已修复）

**修复前：**
```java
// 两步操作，中间崩溃会导致窗口锁存在但没有主通知 ID
Boolean isFirst = stringRedisTemplate.opsForValue()
        .setIfAbsent(aggregateKey, "", AGGREGATE_WINDOW);
// ... insert DB ...
stringRedisTemplate.opsForValue().set(aggregateKey,
        String.valueOf(notification.getId()), AGGREGATE_WINDOW);
```

**修复后：**
```java
// Lua 原子操作：检查 → 写入一步完成
private static final String AGGREGATE_SETNX_SCRIPT =
    "local exists = redis.call('EXISTS', KEYS[1]) " +
    "if exists == 0 then " +
    "  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
    "  return '1' " +
    "else " +
    "  return redis.call('GET', KEYS[1]) " +
    "end";
```

**原理分析：**
原实现分两步：① SETNX(key, "") ② SET(key, notificationId)。如果进程在 ① 和 ② 之间崩溃，窗口锁存在但值为空字符串，后续聚合请求读到空值会走 fallback（创建新通知），导致同一窗口内出现两条独立通知。Lua 脚本保证"检查是否存在 → 写入 ID"是原子的。

#### 问题 2：聚合计数并发竞态（已修复）

**修复前：**
```java
// 先读后写，两个线程同时读到 count=1，都写 count=2
int newCount = mainNotification.getAggregateCount() + 1;
notificationMapper.updateAggregateInfo(mainId, newCount, aggregateTitle);
```

**修复后：**
```java
// SQL 原子递增：aggregate_count = aggregate_count + 1
int newCount = notificationMapper.incrementAggregateCount(mainId);
```

**原理分析：**
MySQL 的 `UPDATE SET aggregate_count = aggregate_count + 1` 在行锁保护下是原子的。即使两个线程同时执行，MySQL 会串行化这两个 UPDATE，保证 count 从 1→2→3 而非 1→2→2。

#### 问题 3：SSE createConnection 竞态（已修复）

**修复前：**
```java
// get + remove + put 三步非原子
SseEmitter oldEmitter = emitters.get(userId);
if (oldEmitter != null) { oldEmitter.complete(); emitters.remove(userId); }
emitters.put(userId, emitter);
```

**修复后：**
```java
// put 原子替换，返回旧值
SseEmitter oldEmitter = emitters.put(userId, emitter);
if (oldEmitter != null) { oldEmitter.complete(); }
```

**原理分析：**
ConcurrentHashMap.put() 是线程安全的原子操作，返回被替换的旧值。原实现 get→remove→put 三步之间，另一个线程可能插入新连接，导致新连接被错误关闭。

### 🟡 中等问题

#### 问题 4：推送模板每次查 DB（已修复）

**修复方案：** ConcurrentHashMap 本地缓存，`computeIfAbsent` 懒加载。

#### 问题 5：resetUnreadByType 非原子（已修复）

**修复前：** HGET → DECRBY → HSET 三步操作
**修复后：** Lua 脚本原子执行

```lua
local typeCount = redis.call('HGET', KEYS[2], ARGV[1])
if typeCount == false or tonumber(typeCount) <= 0 then return 0 end
local total = redis.call('GET', KEYS[1])
if total == false then total = '0' end
local newTotal = tonumber(total) - tonumber(typeCount)
if newTotal < 0 then newTotal = 0 end
redis.call('SET', KEYS[1], tostring(newTotal))
redis.call('HSET', KEYS[2], ARGV[1], '0')
return tonumber(typeCount)
```

#### 问题 6：对账任务全表扫描 OOM（已修复）

**修复方案：** 按 userId 分批查询（每批 500 用户），游标式翻页。

#### 问题 7：MQ 消费失败后 Redis 去重 Key 已写入（已修复）

**修复前：** 消费前 SETNX 写入去重 Key → 消费失败 → RocketMQ 重试 → 被去重跳过
**修复后：** 消费成功后才写入去重 Key，由聚合窗口锁兜底保证幂等

### 🟢 轻微问题

#### 问题 8：getTypeStr 重复定义（已修复）

**修复方案：** 抽取为 `NotificationType` 枚举，消除 `NotificationService` 和 `NotificationAggregator` 中的重复代码。

## 三、技术亮点和面试价值

### 亮点 1：SSE 两步法安全建连（面试价值：⭐⭐⭐⭐⭐）

**问题：** EventSource API 不支持自定义 Header，Token 放 URL 会泄露到日志。
**方案：** POST 获取 30 秒一次性 Ticket → GET 用 Ticket 建连。
**面试话术：** "我们采用两步法解决 SSE 安全建连问题。第一步用 POST 请求（Header 携带 JWT Token）获取一个 30 秒有效的一次性 Ticket，存储在 Redis 中。第二步用 GET 请求携带 Ticket 建立 SSE 连接，服务端通过 getAndDelete 原子操作验证并消费 Ticket。即使 Ticket 泄露到日志，也无法重用。"

### 亮点 2：通知聚合器（面试价值：⭐⭐⭐⭐⭐）

**问题：** 热门笔记被 1000 人点赞，不能生成 1000 条通知。
**方案：** Redis SETNX 5 分钟窗口锁 + Lua 原子操作 + 存储层聚合。
**面试话术：** "我们设计了基于时间窗口的通知聚合器。5 分钟内同一用户、同一类型、同一目标的通知会合并为一条。核心是 Lua 脚本实现的原子 SETNX：窗口内第一条通知写入 DB 并将 ID 存入 Redis，后续通知通过 SQL 原子递增 aggregate_count。这样 1000 个点赞只产生 1 条通知记录，DB 写入量减少 1000 倍。"

### 亮点 3：未读计数 Lua 防负数 + 对账兜底（面试价值：⭐⭐⭐⭐）

**问题：** 并发标记已读时 DECR 可能导致计数变为负数。
**方案：** Lua 脚本保证"读取 → 判断 → 修改"原子性 + 定时对账修复。
**面试话术：** "未读计数使用 Redis INCR/DECR 原子操作，但 DECR 可能导致负数。我们用 Lua 脚本实现安全 DECR：先 GET 判断是否 > 0，再 DECR。同时每 5 分钟执行对账任务，比较 Redis 计数与 MySQL COUNT，以 DB 为准修复差异。对账任务使用 Redisson 分布式锁保证多实例只执行一次，分批查询避免 OOM。"

### 亮点 4：心跳 Pipeline 批量续期（面试价值：⭐⭐⭐）

**问题：** 1000 个在线用户，每 10 秒心跳需要 1000 次 Redis SET。
**方案：** Redis Pipeline 批量执行，1 次网络往返完成 1000 次 SET。

### 亮点 5：MQ 消费幂等（面试价值：⭐⭐⭐⭐）

**问题：** RocketMQ 重试可能导致重复消费。
**方案：** 消费成功后才写入 Redis 去重 Key + 聚合窗口锁兜底。
**面试话术：** "我们采用两级幂等保障：第一级是 Redis msgId 去重，但关键是消费成功后才写入去重 Key，避免消费失败后重试被错误跳过。第二级是聚合器的 SETNX 窗口锁，即使去重 Key 写入失败导致重复消费，聚合器也会将重复通知合并到主通知上，不会产生重复记录。"

## 四、面试 Q&A

### Q1: SSE 和 WebSocket 有什么区别？为什么选 SSE？
**A:** SSE 是单向通信（服务端→客户端），基于 HTTP 协议，天然支持断线重连和事件类型。WebSocket 是双向通信，需要额外的协议升级。通知场景是典型的单向推送，SSE 更轻量：不需要额外的协议处理、天然兼容 HTTP 基础设施（Nginx/CDN/负载均衡）、浏览器原生支持 EventSource API。

### Q2: SSE 长连接会不会占满 Tomcat 线程？
**A:** 会。每个 SSE 连接占用一个 Tomcat 线程。我们的方案：① Tomcat 线程池调大到 400 ② 心跳检测清理死连接 ③ 生产环境可以升级为 WebFlux 非阻塞模型或独立部署 SSE 网关。当前 BIO 模型支撑 ~300 并发连接没问题，超过后需要架构升级。

### Q3: 多实例部署时，用户连接在 A 实例，通知事件在 B 实例消费，怎么推送？
**A:** 当前方案通过 Redis 记录 userId→serverId 映射。B 实例消费事件后，先检查本机 emitters 是否有该用户连接，没有则通知写入 DB + 更新 Redis 未读计数。用户下次打开 APP 时通过 HTTP 接口拉取。生产环境可以升级为 Redis Pub/Sub 跨实例推送。

### Q4: 聚合窗口内第一条通知和后续通知的处理有什么区别？
**A:** 第一条通知：Lua SETNX 成功 → INSERT DB → Redis 存储通知 ID。后续通知：Lua SETNX 失败 → 返回主通知 ID → SQL 原子递增 aggregate_count → 更新聚合标题。被聚合的通知不写 DB（先插入后软删除），减少存储开销。

### Q5: 未读计数为什么不直接用 MySQL COUNT？
**A:** 性能问题。每次打开 APP 都要查未读数，如果用 `SELECT COUNT(*) WHERE is_read=0`，百万级通知表会很慢。Redis INCR/DECR 是 O(1) 操作，QPS 可达 10 万+。但 Redis 可能丢数据（主从切换），所以用定时对账兜底。

## 五、修复前后代码对比总结

| 问题 | 修复前 | 修复后 | 影响 |
|------|--------|--------|------|
| 聚合 SETNX+SET 非原子 | 两步操作 | Lua 原子脚本 | 消除崩溃导致的空窗口锁 |
| 聚合计数并发竞态 | 先读后写 | SQL `count=count+1` | 消除并发丢失计数 |
| SSE 创建竞态 | get+remove+put | put 原子替换 | 消除并发连接泄露 |
| resetUnreadByType 非原子 | HGET→DECRBY→HSET | Lua 原子脚本 | 消除并发多减 |
| MQ 去重 Key 时机 | 消费前写入 | 消费成功后写入 | 消除重试被跳过 |
| 对账全表扫描 | 一次查全部 | 分批游标查询 | 消除 OOM 风险 |
| getTypeStr 重复 | 两处重复定义 | NotificationType 枚举 | 消除代码重复 |
| 模板每次查 DB | 每次 selectByType | ConcurrentHashMap 缓存 | 减少 DB 查询 |

## 六、深度技术分析

### 原子性边界分析

| 操作 | 原子性保证 | 边界条件 |
|------|-----------|---------|
| 聚合窗口锁 | Lua SETNX + SET 原子 | Redis 宕机 → 窗口锁丢失 → 新窗口（可接受） |
| 聚合计数递增 | MySQL 行锁原子 | 主通知被删除 → incrementAggregateCount 返回 0 → fallback 新建 |
| 未读计数 DECR | Lua 读+判断+DECR 原子 | Redis 主从切换 → 计数丢失 → 对账修复 |
| 按类型重置 | Lua HGET+DECRBY+HSET 原子 | 同上 |
| SSE 连接替换 | ConcurrentHashMap.put 原子 | 旧连接 complete() 失败 → 忽略（已从 map 移除） |
| MQ 去重 | 消费成功后 SET | 极小概率重复消费 → 聚合窗口锁兜底 |

### 一致性保证链路

```
MQ 事件 → Redis 去重 → 聚合窗口锁 → DB INSERT/UPDATE → Redis INCR → SSE 推送
                                                              ↑
                                                         对账任务修复
```

每一层都有兜底机制，形成"最终一致性"保证。
