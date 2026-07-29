# 通知聚合机制

> 模块：notification | 核心类：NotificationAggregator、NotificationService | 修复日期：2026-07-29

---

## 1. 业务背景

社交平台的通知场景有一个特点：短时间内同一篇笔记可能被多位用户点赞。如果不做聚合：

- A 赞了你的笔记 → 1 条通知
- B 赞了你的笔记 → 1 条通知  
- C 赞了你的笔记 → 1 条通知

用户打开 App 时会被满屏同类通知淹没，体验极差。而且每条通知都要写 DB、SSE 推送——对基础设施也不友好。

**聚合目标**：5 分钟时间窗口内，同一用户 + 同一类型 + 同一目标的通知合并为一条。标题从 "某某赞了你的笔记" 更新为 "某某等3人赞了你的笔记"，`aggregate_count` 递增。

---

## 2. 架构决策

### 2.1 存储层聚合 vs 展示层聚合

| 方式 | 实现 | DB 写入 | SSE 推送 | 终端复杂度 |
|---|---|---|---|---|
| **存储层聚合**（当前） | 只存一条主通知，聚合不写 DB | O(1)/窗口 | 推送已聚合数据 | 低 |
| 展示层聚合 | 全写，查询时 GROUP BY | O(N)/窗口 | 需后端二次聚合 | 中 |

选择存储层聚合：通知是推送场景——SSE 推送时不能做 GROUP BY（那是 HTTP 查询才有的），必须在写入时就完成聚合。代价是丢失被聚合通知的详情，但通知场景用户不关心"每个点赞人的具体内容"——标题已包含人数。

### 2.2 聚合窗口 Key 设计

`notify:agg:{userId}:{type}:{targetId}`

- `userId`：隔离不同用户（A 被赞 ≠ B 被赞）
- `type`：隔离不同类型（点赞 + 评论不合并——用户需要区分"有人评论了"和"有人点赞了"）
- `targetId`：隔离不同目标（同一用户的不同笔记被赞是独立事件）

三个维度构成**最小聚合单元**。

### 2.3 聚合窗口时长

**5 分钟 Redis TTL**。这个值的权衡：

- **太短（如 30 秒）**：用户连续收到 3 次推送（A 点赞→B 点赞→C 点赞，各一条）
- **太长（如 30 分钟）**：用户 10 分钟后收到另一批点赞，被合并到第一批一起——标题更新但用户不会重新收到推送
- **5 分钟**：用户感知合理——"刚才"收到的通知聚合在一起

### 2.4 修复历史：SETNX-first vs INSERT-first

**原实现（已修复）**：先 INSERT DB 获取 ID，再用 ID 做 SETNX。

```
通知到达 → INSERT DB（获取自增ID）→ SETNX(aggregateKey, ID) → 判断是否第一条
         → 不是第一条？→ DELETE(逻辑删除) → 聚合到主通知
```

问题：
1. 每次聚合都执行 INSERT→DELETE 循环，浪费 DB 资源
2. `uk_aggregate(user_id,type,target_id,notify_date)` 唯一约束曾导致同一天第二次 INSERT 触发 `DuplicateKeyException`

**修复后**：SETNX 先用 `"PENDING"` 占位。

```
通知到达 → SETNX(aggregateKey, "PENDING") → 是否第一条？
         → 是 → INSERT DB → SET aggregateKey = 真实ID
         → 否 → 不写 DB，直接更新主通知的 aggregate_count
```

聚合通知完全跳过 DB 写入，同时删除了过严的唯一约束（降为普通索引 `idx_user_type_target`）。

---

## 3. 源码追踪

### 3.1 核心流程（NotificationAggregator.java:87）

```java
public Notification processWithAggregate(Notification notification) {
    String aggregateKey = "notify:agg:" +
        notification.getUserId() + ":" + notification.getType() + ":" + notification.getTargetId();

    // Step 1: Lua 原子 SETNX，先用 "PENDING" 占位
    DefaultRedisScript<String> script = new DefaultRedisScript<>(AGGREGATE_SETNX_SCRIPT, String.class);
    String result = stringRedisTemplate.execute(script,
        Collections.singletonList(aggregateKey),
        "PENDING",                                  // ARGV[1]
        String.valueOf(AGGREGATE_WINDOW.getSeconds())); // ARGV[2] = 300

    if ("1".equals(result)) {
        // 窗口内第一条：INSERT DB，用真实 ID 替换 "PENDING"
        notification.setAggregateCount(1);
        notificationMapper.insert(notification);
        stringRedisTemplate.opsForValue().set(aggregateKey,
            String.valueOf(notification.getId()), AGGREGATE_WINDOW);
        return notification;
    }

    // 窗口内后续：聚合到主通知（不写 DB）
    Long mainId = Long.parseLong(result);
    notificationMapper.incrementAggregateCount(mainId);     // UPDATE SET count=count+1
    notificationMapper.updateAggregateTitle(mainId, buildAggregateTitle(...));
    return notificationMapper.selectById(mainId);
}
```

**关键设计**（代码为简化版，完整流程含 4 条容错路径：null/empty 检查 118-123、PENDING 重试 126-137、affected≤0 回退 143-148、selectById 空值兜底 162-164）：

**① "PENDING" 占位模式**：SETNX 写入 `"PENDING"` 而不是直接写 ID（因为还没有 ID——需要 INSERT 后才生成）。确认窗口后立即用真实 ID 替换。如果并发场景下另一个线程读到 `"PENDING"`，它会 `sleep(100ms)` 等待重试（源码 127-137 行）。

**② `incrementAggregateCount` 原子递增**：
```sql
UPDATE t_notification SET aggregate_count = aggregate_count + 1, updated_at = NOW()
WHERE id = #{id} AND deleted = 0
```
MySQL 的行锁保证 `aggregate_count = aggregate_count + 1` 是原子的——不需要先 SELECT 再 UPDATE。`deleted = 0` 条件防止更新逻辑删除的记录。

**③ 聚合标题 fallback**（`NotificationAggregator.java:171`）：
```java
// NotificationAggregator.java:172
private String buildAggregateTitle(Integer type, String senderName, int count) {
    NotificationType nt = NotificationType.fromCode(type);
    PushTemplate template = getTemplateWithCache(nt.getName());

    if (template != null && template.getAggregateTitleTemplate() != null) {
        return template.getAggregateTitleTemplate()
            .replace("{sender}", senderName != null ? senderName : "某用户")
            .replace("{count}", String.valueOf(count));
    }

    // 默认格式：模板为空时回退到枚举 defaultAction
    return (senderName != null ? senderName : "某用户") + "等" + count + "人" + nt.getDefaultAction();
}
```
`getDefaultAction()` 返回：LIKE → "赞了你的笔记"，COMMENT → "评论了你的笔记"，FOLLOW → "关注了你"。

### 3.2 Lua 原子脚本（NotificationAggregator.java:72）

```lua
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
  return '1'
else
  return redis.call('GET', KEYS[1])
end
```

**为什么需要 Lua？**

两步非原子实现：`SETNX(key, "")` → `SET(key, id)`。如果进程在两步之间崩溃，窗口锁存在但 ID 为空 → 无主通知 → 所有后续通知走 fallback（独立通知，不聚合）。Lua 保证"检查→写入"原子，避免中间状态。

### 3.3 模板本地缓存（NotificationAggregator.java:56）

```java
private final ConcurrentHashMap<String, PushTemplate> templateCache = new ConcurrentHashMap<>();

private PushTemplate getTemplateWithCache(String typeStr) {
    return templateCache.computeIfAbsent(typeStr, pushTemplateMapper::selectByType);
}
```

`computeIfAbsent` 实现懒加载：第一次查 "LIKE" 模板时从 DB 加载，之后从内存读。模板数据几乎不变，不需要过期策略。

### 3.4 与 processEvent 的协作

```java
// NotificationService.java:61
Notification result = aggregator.processWithAggregate(notification);
if (result.getId().equals(notification.getId())) {
    unreadCountService.incrementUnread(...);
}
```

**判断逻辑**：
- 新建通知：聚合器返回当前 `notification` 对象自身 → `result.getId()` == `notification.getId()` → INCR
- 聚合通知：聚合器返回主通知（来自 DB） → `result.getId()` ≠ `notification.getId()`（后者为 null，未插入） → 不 INCR

关键依赖：聚合路径不调用 `notificationMapper.insert()`，ID 保持 null。

---

## 4. 面试 Q&A

### Q1：聚合窗口内的通知会触发未读 +1 吗？

**答**：不会。

只有窗口内第一条通知（新建主通知时）才会 +1。窗口内后续通知通过 `result.getId().equals(notification.getId())` 判断——聚合通知未插入 DB，ID 为 null，与主通知 ID 不相等，`incrementUnread` 不执行。

**追问**：如果有 Bug 导致聚合通知也调用了 incrementUnread，会有多严重？

**答**：5 分钟内如果有 100 个用户点赞同一篇笔记，未读会变成 100。用户打开 App 看到 "99+" 但通知列表只有 1 条——红点数字与内容数量不匹配。好在对账 Job（UnreadReconcileJob，每 5 分钟）会以 DB 为准修复 Redis，最终收敛。

### Q2：如果 5 分钟窗口内第一个通知的 INSERT 成功了，但用真实 ID 替换 "PENDING" 的 Redis SET 失败了会怎样？

**答**：PENDING 占位有 300 秒 TTL。如果 SET 失败，PENDING 会在 TTL 过期前持续存在。后续通知读到的 `result = "PENDING"`，触发 sleep(100ms) 重试机制（源码 127-137 行）。如果重试后仍然是 PENDING 或 null，走 fallback：创建独立通知。

**后果**：聚合失效——窗口内后续通知都变成独立通知。但不会丢失数据。

**追问**：PENDING 不会导致无限循环吗？

**答**：不会。重试只执行一次（sleep 后检查一次，仍不满足则走 fallback）。不是循环重试。

### Q3：为什么要用存储层聚合而不是展示层聚合？展示层聚合（查询时 GROUP BY + HAVING）不是更简单吗？

**答**：两个理由让展示层聚合在这个场景不合适：

1. **SSE 推送不是 HTTP 查询**：SSE 推送时没有"查询"这一步——推送的是已经生成的数据。展示层聚合只在 HTTP API 查询时生效，SSE 推出去的数据是未聚合的。
2. **通知列表本身就是分页查询**：`SELECT * FROM t_notification WHERE user_id=? ORDER BY created_at DESC LIMIT 20`。如果做展示层聚合，需要在应用层对全量数据做 GROUP BY（或写复杂 SQL），分页边界难以处理（第 2 页的聚合结果可能与第 1 页重叠）。

---

## 5. 生产实验

### 实验：验证聚合窗口内未读不增加

```bash
# 1. 清理旧数据，确保干净状态
curl -s -X POST http://localhost:19013/api/notification/read-all -H "X-User-Id: 10001"

# 2. 第一条通知（创建窗口）
curl -s -X POST http://localhost:19013/api/notification/test/send \
  -H "Content-Type: application/json" \
  -d '{"type":1,"senderId":10002,"senderName":"A","targetUserId":10001,"targetId":9999}'

# 3. 立即查未读（应该是 1）
curl -s http://localhost:19013/api/notification/unread-count -H "X-User-Id: 10001"
# 预期：{"total":1,"details":{"1":1}}

# 4. 第二条通知（同一窗口，应聚合）
curl -s -X POST http://localhost:19013/api/notification/test/send \
  -H "Content-Type: application/json" \
  -d '{"type":1,"senderId":10003,"senderName":"B","targetUserId":10001,"targetId":9999}'

# 5. 再次查未读（应该还是 1，不是 2）
curl -s http://localhost:19013/api/notification/unread-count -H "X-User-Id: 10001"
# 预期：{"total":1}  ← 聚合不增加未读

# 6. 验证 MySQL 只有 1 条记录，aggregate_count=2
mysql -e "SELECT title, aggregate_count FROM t_notification WHERE user_id=10001 AND target_id=9999 AND deleted=0"
# 预期：title="B等2人赞了你的笔记", aggregate_count=2
```

---

## 6. 发散思考

### 6.1 如果聚合窗口不是 5 分钟，而是基于通知数量聚合？

Twitter 的推送策略是"按数量聚合"而不是"按时间聚合"——同一篇推文的点赞超过 N 个后合并为一条 "A 和 B 和另外 10 人赞了"。

**对比**：

| 维度 | 时间窗口（当前） | 数量阈值 |
|---|---|---|
| 实现 | Redis TTL 自动过期 | 需维护计数器 + 阈值判断 |
| 用户体验 | "5 分钟内"的概念清晰 | "超过 3 人"不直观 |
| 极端场景 | 1 分钟内有 100 人点赞 → 1 条聚合 | 100 人分 3 天后达到 → 仍是 1 条？ |
| 延迟 | TTL = 300s 固定 | 取决于用户增长速度 |

当前方案简单可靠。如果未来需要更精细的聚合策略（如推特式的"A 和 B 和另外 10 人赞成"），可以在 `buildAggregateTitle` 中增加判断：count ≤ 2 时展示所有发送者名称，> 2 时展示 "A等X人"。

### 6.2 如果用消息队列缓冲代替实时聚合？

当前流程：**每条通知事件到达 → 立即聚合 → 写入 DB**。替代方案：**事件进入 Kafka → 5 分钟窗口批次处理 → 批量写入**。

**对比**：

| 维度 | 实时聚合（当前） | 窗口批次 |
|---|---|---|
| 延迟 | 毫秒级（SETNX + INSERT 均在一次请求内完成） | 最多 5 分钟（窗口边界累积） |
| DB 压力 | 1 INSERT/窗口 | 1 UPSERT/窗口 |
| 实现复杂度 | 中（Lua + SETNX） | 高（流处理 + Checkpointing） |
| 一致性 | SETNX 失败→独立通知（补偿） | Exactly-Once 语义依赖 Kafka 基础设施 |

当前场景是秒级推送（用户期待立即看到通知），不适合 5 分钟批次——用户点赞后 5 分钟才收到通知是不可接受的。

---

## 7. 跨文档引用

- 架构文档：`01-notification-module.md` §6.1 通知生成流程、§6.2 聚合机制
- 测试记录：`02-notification-test-record.md` 测试 4（聚合验证：2 likes→1 record）
