# 通知聚合机制

> 模块：notification | 端口：19013 | 日期：2026-07-29

---

## 1. 业务背景

社交平台的通知场景有显著特点：短时间内同一笔记可能被多位用户点赞。如果不做聚合，A 赞了你的笔记 → 1 条通知；B 赞了你的笔记 → 1 条通知；C 赞了你的笔记 → 1 条通知……用户打开 App 时会被大量同类通知淹没。

**聚合目标**：5 分钟时间窗口内，同一用户 + 同一类型 + 同一目标的通知合并为一条，标题更新为 "A 等 3 人赞了你的笔记"。

---

## 2. 架构决策

### 决策：存储层聚合 vs 展示层聚合

| 方式 | 实现 | DB 写入量 | 实现复杂度 |
|---|---|---|---|
| **存储层聚合**（当前方案） | 只保留一条主通知，被聚合的逻辑删除 | 减少 N 倍 | 中 |
| 展示层聚合 | 全部写入，查询时 GROUP BY | 不减少 | 低 |

**选择存储层聚合**：
- 减少 DB 写入量（高并发点赞时效果显著）
- 主动推送（SSE）时无需后端聚合，直接发送已聚合的数据
- 缺点是丢失被聚合通知的详情（但通知场景不关心每个点赞人的详情——标题已包含人数）

### 决策：聚合窗口时长

- **5 分钟**：用户感知合理——"刚才"收到的通知聚合在一起
- **Redis TTL**：SETNX 写入时设置 300 秒 TTL，自动过期

---

## 3. 源码追踪（修复后版本）

### 3.1 核心流程

```java
// 文件：service/NotificationAggregator.java:87 (修复后)
public Notification processWithAggregate(Notification notification) {
    // 聚合 Key = userId:type:targetId
    String aggregateKey = "notify:agg:" +
            notification.getUserId() + ":" +
            notification.getType() + ":" +
            notification.getTargetId();

    // Lua 原子操作：SETNX 窗口锁
    String result = stringRedisTemplate.execute(AGGREGATE_SETNX_SCRIPT,
            Collections.singletonList(aggregateKey),
            "PENDING",               // ARGV[1]: 临时占位值
            String.valueOf(300));    // ARGV[2]: TTL 秒数

    if ("1".equals(result)) {
        // 窗口内第一条 → INSERT DB
        notification.setAggregateCount(1);
        notificationMapper.insert(notification);

        // 用真实 ID 替换 "PENDING"
        stringRedisTemplate.opsForValue().set(aggregateKey,
                String.valueOf(notification.getId()),
                Duration.ofMinutes(5));
        return notification;
    }

    // 窗口内后续 → 聚合
    Long mainId = Long.parseLong(result);
    int affected = notificationMapper.incrementAggregateCount(mainId);

    // 更新聚合标题
    String title = buildAggregateTitle(type, senderName, newCount);
    notificationMapper.updateAggregateTitle(mainId, title);

    return mainNotification;
}
```

### 3.2 Lua 原子脚本

```lua
-- AGGREGATE_SETNX_SCRIPT
-- 返回值：ARVG[1] = "PENDING" 首次 → "1"
--       已存在 → GET(KEY)
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
  return '1'
else
  return redis.call('GET', KEYS[1])
end
```

**为什么用 Lua？**

原实现分两步：`SETNX` 写入空值 → `SET` 写入 ID。如果进程在两步之间崩溃：
- 窗口锁存在但 ID 为空 → 后续通知无法聚合，走 fallback 独立通知

Lua 保证"检查 → 写入"原子性，避免中间状态。

### 3.3 模板本地缓存

```java
// 文件：service/NotificationAggregator.java:56
private final ConcurrentHashMap<String, PushTemplate> templateCache =
        new ConcurrentHashMap<>();

private PushTemplate getTemplateWithCache(String typeStr) {
    return templateCache.computeIfAbsent(typeStr,
            pushTemplateMapper::selectByType);
}
```

**为什么需要缓存？**
- `buildAggregateTitle()` 需要查模板表来渲染聚合标题
- 模板数据几乎不变，`computeIfAbsent` 实现懒加载缓存
- 避免每次聚合都查 DB

### 3.4 修复历史

**原实现（buggy）**：
```java
// INSERT first, then SETNX
notificationMapper.insert(notification);
String result = executeLuaSETNX(aggregateKey, notificationId);
if ("1".equals(result)) { return notification; }
else {
    notificationMapper.deleteById(notificationId); // logical delete
    // aggregate to main...
}
```

**问题**：
1. `uk_aggregate(user_id,type,target_id,notify_date)` 唯一约束——同一天第二次 INSERT 失败
2. 无效的 INSERT→DELETE 循环浪费 DB 资源
3. 聚合的 notification 被逻辑删除，产生垃圾数据

**修复**：
1. `uk_aggregate` 唯一约束 → 普通索引（`idx_user_type_target`）
2. SETNX 先用 `"PENDING"` 占位 → 确认窗口后 INSERT
3. 聚合通知不写 DB（跳过 INSERT）

---

## 4. 面试 Q&A

### Q1: 聚合窗口的 Key 为什么选择 `userId:type:targetId`？

**A**: 
- `userId`：隔离不同用户（A 被赞和 B 被赞是两条通知）
- `type`：隔离不同类型（点赞和评论不聚合——用户需要知道"有人评论了"和"有人点赞了"是不同事件）
- `targetId`：隔离不同目标（同一篇笔记 A 的赞和笔记 B 的赞不聚合——用户关心"哪篇笔记被赞"）
- 三者组合 = 最小聚合单元

### Q2: 如果用户在 5 分钟窗口内不断收到点赞会怎样？

**A**:
1. 第 1 次：创建主通知，未读 +1
2. 第 2-N 次：聚合到主通知（更新 aggregate_count 和 title），未读不变
3. 5 分钟后 Redis Key 过期，下一个通知创建新窗口 → 未读 +1

### Q3: 怎么做聚合标题的 fallback？模板不存在时怎么办？

**A**: 
```java
// 默认聚合格式
return (senderName != null ? senderName : "某用户")
    + "等" + count + "人" + nt.getDefaultAction();
// 输出示例："某用户等3人赞了你的笔记"
```

`NotificationType` 枚举定义了 `defaultAction`：
- LIKE → "赞了你的笔记"
- COMMENT → "评论了你的笔记"
- FOLLOW → "关注了你"

### Q4: 被聚合的通知号（is_aggregated=1, deleted=1）会被查询到吗？

**A**: 不会。MyBatis-Plus `@TableLogic` 自动过滤 `deleted=1` 的记录，查询列表和计数都不会包含。

---

## 5. 生产实验

### 实验 1：验证聚合计数更新

```bash
# 1. 发送 3 条同一 note 的点赞
for i in {1..3}; do
curl -X POST http://localhost:19013/api/notification/test/send \
  -d '{"type":1,"senderId":'$((10001+i))',"senderName":"用户'$i'","targetUserId":10001,"targetId":999}'
done

# 2. 验证
mysql> SELECT title, aggregate_count FROM t_notification
       WHERE user_id=10001 AND target_id=999;
# 预期：title = "用户3等3人赞了你的笔记", aggregate_count = 3
```

### 实验 2：验证聚合窗口过期后创建新通知

```bash
# 1. 发送第一条（创建窗口）
curl ... -d '{"targetId":888, ...}'

# 2. 等 5 分钟以上（窗口过期）

# 3. 发送第二条（新窗口）
curl ... -d '{"targetId":888, ...}'

# 4. 验证：MySQL 有 2 条未删除记录（属于不同窗口）
mysql> SELECT COUNT(*) FROM t_notification WHERE target_id=888 AND deleted=0;
# 预期：2
```

---

## 6. 发散章节

### 6.1 如果聚合窗口内第 100 个赞到达，怎么保证 aggregate_count 的精确性？

目前使用 `UPDATE ... SET aggregate_count = aggregate_count + 1`（MySQL 原子递增），天然保证计数精确。

### 6.2 如果瞬时大量不同目标被同时点赞（热点问题）？

聚合机制按 `targetId` 分片，天然分散了写入压力。但注意 Redis Key 也是按 `targetId` 分片——不会出现单 Key 热点。

### 6.3 聚集通知的 isRead 状态如何管理？

聚合通知只有一个 `is_read` 字段。用户标记已读后，整个聚合组（包含所有被聚合的点赞）都变为已读。如果用户想分别标记已读，需要使用展示层聚合方案。
