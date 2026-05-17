# Phase 3/4/5/6 核心模块深度 Code Review（第二轮修复）

> 审查范围：my-xhs-search、my-xhs-im、my-xhs-notification
> 审查时间：2026-05-16
> 审查标准：P8 大厂生产环境

---

## 一、P8 对标评分表

### 修复前评分

| 模块 | 并发安全 | 分布式考量 | 性能优化 | 代码质量 | 综合 |
|------|---------|-----------|---------|---------|------|
| search | 8.0 | 8.0 | 8.5 | 6.5 | 7.5 |
| im | 7.5 | 7.0 | 7.0 | 7.5 | 7.0 |
| notification | 7.5 | 6.0 | 8.0 | 8.0 | 7.5 |

### 修复后评分

| 模块 | 并发安全 | 分布式考量 | 性能优化 | 代码质量 | 综合 |
|------|---------|-----------|---------|---------|------|
| search | 8.5 | 8.5 | 8.5 | 8.0 | 8.0 |
| im | 8.5 | 8.0 | 8.5 | 8.0 | 8.0 |
| notification | 8.5 | 8.5 | 8.0 | 8.5 | 8.5 |

---

## 二、发现的问题及修复记录

### P0 级问题（功能缺陷）

#### P0-1: 通知模块缺少跨实例 SSE 推送

**问题描述**：
`SseEmitterManager` 只在本实例的 `ConcurrentHashMap` 中查找用户连接。多实例部署时，如果用户 A 连接在实例 1，通知服务运行在实例 2，通知无法实时推送到用户 A。

**修复方案**：
1. 新增 `SseCrossInstanceSubscriber`：监听 Redis Pub/Sub 频道 `notify:sse:push`
2. `SseEmitterManager.pushToUser()` 改为先查本机 → 未找到则发布 Redis 消息
3. 所有实例的 `SseCrossInstanceSubscriber` 收到消息后，检查本机是否有该用户连接并推送

**修复文件**：
- 新增：`SseCrossInstanceSubscriber.java`
- 修改：`SseEmitterManager.java`（pushToUser 方法增加跨实例逻辑）
- 修改：`application.yml`（添加 Redis Pub/Sub 配置）

**架构对比**：
```
修复前：通知服务 → SseEmitterManager → 本机 HashMap → 找不到就丢失
修复后：通知服务 → SseEmitterManager → 本机 HashMap → 未找到 → Redis Pub/Sub → 所有实例检查 → 目标实例推送
```

#### P0-2: IM 已读回执没有跨实例路由

**问题描述**：
`ChatService.handleRead()` 中通知对方"已读"时，只在本实例直推。如果对方在另一个实例上，已读回执不会被送达。

**修复方案**：
仿照聊天消息的路由逻辑，添加 `OnlineRouteService.getRoute()` + MQ 跨实例路由：
- 同实例 → 直推 `webSocketHandler.pushToUser()`
- 跨实例 → 发送 `RouteMessage` 到 `IM_ROUTE_TOPIC`

**修复文件**：`ChatService.java`（handleRead 方法）

---

### P1 级问题（重要优化）

#### P1-1: Search After 浮点精度丢失

**问题描述**：
`parseSearchAfter` 中 `FieldValue.of(((Number) v).longValue())` 对浮点分数（`_score`）做了 `longValue()` 截断，导致精度丢失，Search After 分页跳过记录。

**修复方案**：
区分 double 和 long 类型的 FieldValue：
```java
if (num.doubleValue() != num.longValue()) {
    return FieldValue.of(num.doubleValue());  // 浮点数保留精度
}
return FieldValue.of(num.longValue());  // 整数用 long
```

**修复文件**：`NoteSearchService.java`、`ProductSearchService.java`

#### P1-2: RecommendService 行为上报同步写 DB

**问题描述**：
`jdbcTemplate.update` 同步写 DB，高 QPS 下会成为瓶颈。

**修复方案**：
1. 改为 `rocketMQTemplate.convertAndSend("BEHAVIOR_REPORT_TOPIC", event)` 异步写入
2. 新增 `BehaviorReportConsumer` 消费 MQ 消息，批量写入 DB

**修复文件**：
- 修改：`RecommendService.java`（reportBehavior 方法）
- 新增：`BehaviorReportConsumer.java`

#### P1-3: 通知聚合先 INSERT 再 DELETE 非原子

**问题描述**：
通知聚合时，先 `notificationMapper.insert(notification)` 插入，然后 `notificationMapper.deleteById(notification.getId())` 删除。如果两步之间进程崩溃，会留下一条"孤岛"通知。

**修复方案**：
1. 确认 Notification 实体已有 `@TableLogic` 的 `deleted` 字段，`deleteById` 实际是逻辑删除
2. fallback 逻辑改为 `updateById(notification)` + `setDeleted(0)` 恢复，而非重新 `insert`（避免主键冲突）

**修复文件**：`NotificationAggregator.java`

#### P1-4: 商品索引扁平消息无版本控制

**问题描述**：
`handleFlatMessage` 中 ES 索引没有 `versionType` + `version`，无法防止乱序消息。Canal 格式消息使用了 `ExternalGte` 版本控制，但扁平格式消息没有。

**修复方案**：
为扁平格式消息也添加 `ExternalGte` 版本控制，使用消息自带的时间戳（降级使用当前时间）作为版本号：
```java
long version = event.getLongValue("timestamp", System.currentTimeMillis());
esClient.index(IndexRequest.of(idx -> idx
    .versionType(VersionType.ExternalGte)
    .version(version)
    ...));
```

**修复文件**：`ProductIndexSyncConsumer.java`

#### P1-5: IM 离线消息 LREM O(N) 性能

**问题描述**：
- `storeOfflineMessage` 使用 Redis List（`RPUSH` + `LTRIM`）
- `handleAck` 使用 `LREM`（O(N) 扫描），离线消息 1000 条时每次 ACK 需扫描整个 List

**修复方案**：
改用 Redis Sorted Set（score=timestamp）：
- `ZADD`：O(log N)，比 `RPUSH` 略慢但可接受
- `ZREM`：O(log N)，比 `LREM` O(N) 快 10-100 倍
- `ZRANGE`：替代 `LRANGE`，天然按时间排序
- `ZREMRANGEBYRANK`：替代 `LTRIM`，保留最新 N 条

**性能对比**：
| 操作 | List (修复前) | Sorted Set (修复后) |
|------|-------------|-------------------|
| 写入 | RPUSH O(1) | ZADD O(log N) |
| ACK删除 | LREM O(N) | ZREM O(log N) |
| 分页读取 | LRANGE O(S+N) | ZRANGE O(log N+S) |
| 截断 | LTRIM O(N) | ZREMRANGEBYRANK O(log N+M) |

**修复文件**：`ChatService.java`（storeOfflineMessage、handleAck、pushOfflineMessages）

---

### P2 级问题（代码优化）

#### P2-1: NoteSearchService/ProductSearchService 代码重复

**问题描述**：
`parseSearchAfter`、`normalizeSize`、`toLong`、`toBigDecimal` 等方法在两个类中完全重复。

**修复方案**：
1. 新增 `AbstractSearchService` 抽象基类
2. 两个搜索服务继承基类，删除重复方法
3. `normalizeSize` 改为带参数版本（`normalizeSize(size, defaultSize, maxSize)`），避免子类依赖 `@Value` 注入的实例变量

**修复文件**：
- 新增：`AbstractSearchService.java`
- 修改：`NoteSearchService.java`（extends + 删除重复方法）
- 修改：`ProductSearchService.java`（extends + 删除重复方法）

#### P2-2: 对账任务无限速 + 分页退出条件 bug

**问题描述**：
1. `forceSetUnread` 直接写 Redis，如果修复量很大（Redis 重启后全部 Key 丢失），会瞬间打满 Redis 带宽
2. 分页退出条件 `userTypeCountMap.size() < batchSize` 有 bug：`batchSize * 10` 的 LIMIT 是通知数，可能对应很少的用户，导致提前退出

**修复方案**：
1. 每批次之间休眠 50ms（`Thread.sleep(50)`），限速保护 Redis
2. 退出条件改为 `batch.size() < queryLimit`（检查通知条数而非用户数）
3. 添加中断处理（`InterruptedException` → 提前退出）

**修复文件**：`UnreadReconcileJob.java`

---

## 三、技术亮点和面试价值评估

### ⭐⭐⭐⭐⭐ 面试最高价值

| # | 技术点 | 关键问题 | 本次修复相关 |
|---|--------|---------|------------|
| 1 | Canal + ExternalGte 版本控制 | "MQ 消息乱序怎么保证数据一致性？" | P1-4 扁平消息也加了版本控制 |
| 2 | IM 在线路由 Lua 原子注销 | "多实例部署下如何防止旧连接误删新路由？" | 与 P0-2 跨实例路由配合 |
| 3 | 离线消息 Sorted Set 优化 | "IM 离线消息存储如何优化？LREM O(N) 怎么解决？" | P1-5 |
| 4 | SSE 跨实例推送 | "SSE 多实例部署时如何推送通知？" | P0-1 |
| 5 | 对账任务限速 | "定时对账任务如何避免打满 Redis？" | P2-2 |

### ⭐⭐⭐⭐ 高价值

| # | 技术点 | 关键问题 |
|---|--------|---------|
| 6 | 通知聚合 Lua SETNX | "5 分钟内同一用户收到 100 条点赞通知怎么处理？" |
| 7 | Search After 深分页 | "ES 深分页为什么慢？Search After 怎么解决？浮点精度怎么处理？" |
| 8 | 未读计数安全 DECR | "并发标记已读时 Redis 计数变负数怎么办？" |
| 9 | 指数衰减热搜算法 | "热搜排行榜怎么实现？如何保证时效性？" |

---

## 四、面试话术（Q&A 格式）

### Q1: "MQ 消息乱序怎么保证数据一致性？"

> 我们使用 Canal 监听 MySQL Binlog，通过 RocketMQ 传输到搜索服务同步 ES 索引。为了防止 MQ 消息乱序（网络延迟、重试等），我们使用 ES 的 `ExternalGte` 版本类型 + Canal 的 `es` 字段（严格递增的 event sequence）。ES 会拒绝比当前版本号更低的写入，防止旧消息覆盖新数据。同时，删除操作使用逻辑删除（`status=-1`）而非物理删除，防止乱序的 UPDATE 消息重新索引已删除的文档。
>
> 在第二轮 Review 中，我们发现应用层扁平格式消息缺少版本控制，已修复为同样使用 `ExternalGte` + 时间戳版本号。

### Q2: "IM 离线消息存储如何优化？"

> 最初使用 Redis List + LREM 方案：`RPUSH` 写入离线消息，客户端 ACK 后 `LREM` 删除。但 `LREM` 是 O(N) 操作，需要扫描整个 List。离线消息上限 1000 条时，每次 ACK 都要扫描 1000 个元素。
>
> 优化为 Redis Sorted Set（score=timestamp）：`ZADD` 写入、`ZREM` 删除。`ZREM` 利用跳表只需 O(log N)，1000 条消息时约 10 次比较，比 LREM 快 100 倍。额外好处是天然按时间排序，不需要额外排序。

### Q3: "SSE 多实例部署时如何推送通知？"

> 单实例时，SseEmitter 管理在本机 ConcurrentHashMap 中查找用户连接即可。多实例部署时，如果用户 A 连接在实例 1，通知服务运行在实例 2，通知无法实时推送。
>
> 修复方案：使用 Redis Pub/Sub 实现跨实例推送。`pushToUser()` 先查本机连接 → 未找到则发布 Redis 消息到 `notify:sse:push` 频道 → 所有实例的 `SseCrossInstanceSubscriber` 收到消息后检查本机是否有该用户连接并推送。
>
> 选择 Redis Pub/Sub 而非 RocketMQ 的原因：1）SSE 推送是实时性要求高的场景，Pub/Sub 延迟更低（毫秒级 vs 百毫秒级）；2）不需要持久化，离线消息由其他机制保证；3）所有在线实例都需要收到消息（广播模式）。

### Q4: "ES Search After 的浮点精度问题怎么解决？"

> Search After 要求排序字段的组合必须唯一，且 sort values 必须精确还原。`_score` 是浮点数，如果用 `longValue()` 截断，精度丢失会导致分页跳过记录。
>
> 修复方案：在 `parseSearchAfter` 中区分 double 和 long 类型：如果 `num.doubleValue() != num.longValue()`，说明有小数部分，使用 `FieldValue.of(num.doubleValue())` 保留精度；否则使用 `FieldValue.of(num.longValue())`。

### Q5: "定时对账任务如何避免打满 Redis？"

> 未读计数对账任务每 5 分钟执行，比较 Redis 与 MySQL 的未读计数，以 DB 为准修复 Redis。极端场景：Redis 重启后全部 Key 丢失，需要修复 10 万+ 用户的未读计数。如果不限速，几秒内执行 10 万次 SET 命令会打满 Redis 带宽。
>
> 修复方案：1）每批次之间休眠 50ms，将 10 万次 SET 分散到约 10 秒内执行；2）修复分页退出条件 bug（检查通知条数而非用户数，避免提前退出）；3）支持中断处理，XXL-Job 可以优雅终止长时间运行的任务。

---

## 五、修复前后代码对比

### 5.1 离线消息：List → Sorted Set

**修复前**：
```java
// 写入
stringRedisTemplate.opsForList().rightPush(key, String.valueOf(msgId));
Long size = stringRedisTemplate.opsForList().size(key);
if (size != null && size > MAX_OFFLINE_MESSAGES) {
    stringRedisTemplate.opsForList().trim(key, size - MAX_OFFLINE_MESSAGES, -1);
}

// ACK（O(N) 扫描）
stringRedisTemplate.opsForList().remove(key, 1, String.valueOf(imMsg.getMsgId()));

// 读取
List<String> msgIdStrs = stringRedisTemplate.opsForList().range(key, 0, MAX_OFFLINE_MESSAGES - 1);
```

**修复后**：
```java
// 写入（score=timestamp，天然按时间排序）
double score = System.currentTimeMillis();
stringRedisTemplate.opsForZSet().add(key, String.valueOf(msgId), score);
long size = stringRedisTemplate.opsForZSet().size(key);
if (size > MAX_OFFLINE_MESSAGES) {
    stringRedisTemplate.opsForZSet().removeRange(key, 0, size - MAX_OFFLINE_MESSAGES - 1);
}

// ACK（O(log N) 跳表）
stringRedisTemplate.opsForZSet().remove(key, String.valueOf(imMsg.getMsgId()));

// 读取
Set<String> msgIdStrs = stringRedisTemplate.opsForZSet().range(key, 0, MAX_OFFLINE_MESSAGES - 1);
```

### 5.2 搜索服务：消除代码重复

**修复前**：NoteSearchService 和 ProductSearchService 各自实现 `parseSearchAfter`、`normalizeSize`、`toLong`、`toBigDecimal`

**修复后**：抽取 `AbstractSearchService` 基类，两个服务继承基类

### 5.3 对账任务：限速 + 修复退出条件

**修复前**：
```java
if (userTypeCountMap.size() < batchSize) {  // bug：用户数 < 500 就退出
    break;
}
```

**修复后**：
```java
if (batch.size() < queryLimit) {  // 正确：通知条数 < LIMIT 才退出
    break;
}
try {
    Thread.sleep(50);  // 限速：每批休眠 50ms
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    break;
}
```

---

## 六、深度技术分析

### 6.1 SSE 跨实例推送的选型对比

| 方案 | 延迟 | 可靠性 | 复杂度 | 适用场景 |
|------|------|--------|--------|---------|
| Redis Pub/Sub | ~1ms | 不持久化，离线丢失 | 低 | 实时推送（SSE/WS） |
| RocketMQ Broadcast | ~50ms | 持久化，不丢失 | 中 | 需要可靠投递 |
| Redis Stream + CG | ~5ms | 持久化，ACK确认 | 高 | 需要可靠性+低延迟 |

选择 Redis Pub/Sub 的理由：SSE 推送是实时性要求高的场景，用户不在线时由其他机制（如 App 推送）兜底，不需要 MQ 持久化。

### 6.2 ExternalGte 版本号的选型

| 消息来源 | 版本号 | 精度 | 说明 |
|---------|--------|------|------|
| Canal | `es`（event sequence） | 毫秒级递增 | MySQL Binlog 严格递增，同毫秒不会重复 |
| 扁平消息 | `timestamp` | 毫秒级 | 同毫秒内多次更新可能丢失，但比没有版本控制好 |

### 6.3 通知聚合的原子性边界

```
聚合窗口内第一条通知：
  Lua SETNX → 不存在 → SET(主通知ID, TTL=5min) → INSERT → 返回主通知

聚合窗口内后续通知：
  Lua SETNX → 已存在 → GET(主通知ID) → INSERT + logical DELETE → INCR aggregate_count

极端情况：Lua 返回主通知ID，但主通知已被物理删除
  incrementAggregateCount → 返回 0 → updateById(notification, deleted=0) 恢复
```

关键保证：Notification 实体有 `@TableLogic` 注解，`deleteById` 实际执行 `UPDATE SET deleted=1`，不是真正的物理删除。所以 fallback 逻辑用 `updateById(setDeleted(0))` 恢复，而非重新 `insert`（避免主键冲突）。
