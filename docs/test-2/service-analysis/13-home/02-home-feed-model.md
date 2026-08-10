# Home Feed 推拉混合模型 — 深度技术分析

> 关联源码：`FeedService.java` / `FeedPushConsumer.java` / `FeedCleanupJob.java`

---

## 业务背景

社交平台的 Feed 流面临核心矛盾：**写扩散**（发布时推给所有粉丝）和**读扩散**（阅读时实时聚合）的取舍。

| 方案 | 写入开销 | 读取开销 | 大V问题 |
|---|---|---|---|
| 纯推（写扩散） | O(粉丝数) | O(1) | 大V百万粉丝→百万次写入 |
| 纯拉（读扩散） | O(1) | O(关注数) | 每次读实时查 |
| **推拉混合** | 普通用户推，大V拉 | O(1) + O(大V数) | 大V走拉模式 |

---

## 推拉混合模型

### 判断标准

```java
bigVThreshold = 100000  // 粉丝数 ≥ 10 万 = 大V
```

### 写路径

```
笔记发布 → RocketMQ FEED_TOPIC
                │
       ┌────────▼────────┐
       │ FeedPushConsumer │
       └────────┬────────┘
                │
      ┌─────────┴──────────┐
      ▼                     ▼
  是 大V                    否 — 普通用户
  (拉模式)                  (推模式)
  ZADD outbox              遍历粉丝列表
  → 发件箱                 Pipeline ZADD
  (1 次写入)               到每个粉丝收件箱
                            (N 次写入)
```

**大V走拉模式的原因**：100 万粉丝的推模式写入 = 100 万次 ZADD。Pipeline 分批（batch=500）需 2000 次网络往返，单次往返约 1ms，仅 Redis 操作耗时约 2 秒，加上粉丝 ID 分页遍历和断点写入，总计数十秒可完成。但更严重的是 Redis 负载——百万级 ZADD 瞬间打满 Redis CPU。拉模式只需 1 次 ZADD 到发件箱，粉丝读时实时拉取。

### 读路径

```
用户打开首页 → GET /api/home/feed
                    │
           ┌────────▼────────┐
           │  获取笔记 ID     │
           │                 │
           │ ① 收件箱:       │
           │ ZREVRANGEBYSCORE│
           │ inbox:{uid}     │
           │ 0 ~ cursor-0.001│
           │                 │
           │ ② 大V发件箱:     │
           │ Pipeline 读取   │
           │ 关注的大V的发件箱 │
           │                 │
           │ ③ 合并 + 排序   │
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │  2 层并行聚合    │
           │  L1: 笔记+社交   │
           │  L2: 作者+计数   │
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │  NoteCardVO[]   │
           └─────────────────┘
```

---

## 推模式实现（写扩散）

### 批量 Pipeline

```java
// FeedPushConsumer.pushToFollowers()
// 粉丝 ZSet 分页遍历，batch=500
while (cursor < totalFollowers) {
    Set<String> followerIds = stringRedisTemplate.opsForZSet()
            .range(followerKey, cursor, cursor + batchSize - 1);

    // Pipeline：1 次网络往返 = 500 次 ZADD
    stringRedisTemplate.executePipelined(connection -> {
        for (String followerId : followerIds) {
            byte[] inboxKey = (FEED_INBOX + followerId).getBytes();
            connection.zSetCommands().zAdd(inboxKey, publishTime, noteIdBytes);
            connection.keyCommands().expire(inboxKey, expireSeconds);
        }
        return null;
    });
    cursor += batchSize;
}
```

**优化点**：500 个粉丝的写扩散从 500 次 Redis 往返 → 1 次 Pipeline，往返次数降低 500 倍。

### 断点续推

```
推送进度写入 Redis：
  myxhs:feed:push:progress:{localMsgId} = {cursor}  (TTL=1h)
  → String: 当前已推送到的粉丝游标
  → Hash:   total（总粉丝数）、status（completed）

崩溃恢复：
  MQ 重试 → 查 progress → cursor 非空 → 从中断处继续
  ZADD 天然幂等 → 已推送的不重复
```

---

## 拉模式实现

### 大V 发件箱

```java
// FeedPushConsumer — 大V 路径
String outboxKey = RedisKeyConstants.FEED_OUTBOX + authorId;
stringRedisTemplate.opsForZSet().add(outboxKey, String.valueOf(noteId), publishTime);
stringRedisTemplate.expire(outboxKey, Duration.ofDays(inboxMaxDays));
```

### 大V 检测缓存

从关注列表中筛选大V时，使用 **MGET 批量查询**（1 次网络往返替代逐个 GET 的 N 次）：

```java
private List<Long> getFollowingBigVIds(Long userId) {
    // 1. 从 Redis 关注 ZSet 获取关注列表
    String followingKey = RedisKeyConstants.FOLLOW_LIST + userId;
    Set<String> followingIds = stringRedisTemplate.opsForZSet()
            .reverseRange(followingKey, 0, 499);

    // 2. MGET 批量查询大V缓存标记（1 次往返）
    List<String> bigVKeys = followingIds.stream()
            .map(fid -> "myxhs:user:bigv:" + fid)
            .collect(Collectors.toList());
    List<String> bigVValues = stringRedisTemplate.opsForValue().multiGet(bigVKeys);

    // 3. 筛选标记为 "1" 的用户
    for (int i = 0; i < followingList.size(); i++) {
        if ("1".equals(bigVValues.get(i))) {
            bigVIds.add(Long.valueOf(followingList.get(i)));
        }
    }
    return bigVIds;
}
```

**为什么关注列表只取前 500 条**：Redis ZREVRANGE 取关注列表的前 500 条。用户关注数有限，500 条覆盖绝大部分场景。

**缓存缺失（null）不处理**：当大V缓存不存在时，不会回退去查 ZCARD——只信任缓存。由 `checkBigV()` 在推送时实时判定并刷新缓存。

### 大V 发件箱 Pipeline 读取

```java
// FeedService.pullBigVOutbox()
// Pipeline 读取所有大V的发件箱（1 次网络往返替代 N 次）
List<Object> pipelineResults = stringRedisTemplate.executePipelined(connection -> {
    for (Long bigVId : bigVIds) {
        String outboxKey = FEED_OUTBOX + bigVId;
        connection.zSetCommands().zRevRangeByScoreWithScores(
                outboxKey.getBytes(), 0, maxScore, 0, perBigV);
    }
    return null;
});
```

`perBigV = max(1, size / bigVIds.size())`——每页 20 条、用户关注 5 个大V时，每个大V取 4 条。

### 合并排序 + 游标精度

```java
// FeedService.mergeAndSort()
// 合并收件箱 + 大V发件箱 → 按 score 降序 → 取 top size
Stream.concat(stream1, stream2)
        .filter(t -> t.getScore() != null)
        .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
        .limit(size)
        .collect(Collectors.toList());
```

**游标分页**：
- 首次请求 `lastScore = Double.MAX_VALUE`（从最新开始）
- 翻页传 `lastScore - 0.001`（避免重复，`0.001` 是毫秒级精度，正常发布时间戳不会这么密集）
- `formatCursor()` 处理科学计数法：整数时间戳用 `longValue()`，浮点保留完整精度

**为什么 TTL=10 分钟**：
- 1 小时太长：用户从 9.9 万粉丝涨到 10.1 万，缓存仍显示"非大V"→推模式写入 10 万粉丝→Redis 写入风暴
- 10 分钟：可接受的不一致窗口

---

## 收件箱裁剪

### 为什么不在推送时裁剪

早期实现在 `FeedPushConsumer` 的 Pipeline 中加 Lua 条件裁剪（ZADD + ZCARD + ZREMRANGEBYRANK）。但大多数用户收件箱远未达到 500 条上限，实时裁剪是浪费的。

### 改为 XXL-Job 定时裁剪

```java
// FeedCleanupJob — 每天凌晨 3 点执行
// 阶段 1：删除 7 天前的过期数据
stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoffTime);

// 阶段 2：ZCARD > 500 时裁剪到 500
Long card = stringRedisTemplate.opsForZSet().zCard(key);
if (card != null && card > inboxMaxSize) {
    stringRedisTemplate.opsForZSet().removeRange(key, 0, card - inboxMaxSize - 1);
}
```

SCAN 遍历所有 `myxhs:feed:inbox:*` 和 `myxhs:feed:outbox:*`，每 Key 处理间隔 50ms 限速。

---

## 面试 Q&A

**Q: 怎么判断一个用户是不是大V？**
A: Redis 缓存 `myxhs:user:bigv:{userId}`，TTL=10 分钟。缓存未命中时 ZCARD 粉丝数，>=10 万标记为大V。缓存的作用是避免每次发笔记都 ZCARD。

**Q: 大V阈值 10 万怎么来的？**
A: 对标微博/Instagram。10 万粉丝以下的用户使用推模式，每条笔记的写扩散压力可控。10 万以上走拉模式，避免一次笔记发布触发百万级 Redis ZADD。

**Q: 推送一半崩溃了怎么办？**
A: 断点续推。每批推送完成后把游标写入 Redis `myxhs:feed:push:progress:{localMsgId}`。MQ 重试时查进度，从断点继续。ZADD 天然幂等。

**Q: 收件箱无限膨胀怎么办？**
A: 两层控制：7 天 TTL（ZSet EXPIRE） + XXL-Job 每日裁剪（ZCARD > 500 时 ZREMRANGEBYRANK）。

---

## 生产实验

当前单实例环境，已验证：

- **MQ 消费链路**：`FeedPushConsumer` 代码确认订阅 `FEED_TOPIC`，消费组 `home-consumer-group`
- **推模式代码路径**：`pushToFollowers()` 中 Pipeline 批量 ZADD 逻辑已审查
- **大V检测**：`checkBigV()` 缓存 + ZCARD 降级逻辑已审查

**未实测**（需内容服务配合发笔记事件 + Redis 中有真实的关注关系和粉丝数据）：
- Feed 流读取（收件箱无数据时返回空列表）
- Feed 流翻页（游标分页确认）
- 大V缓存过期重新计算

这些需要在 curl 测试阶段通过 `FeedTestController`（dev profile）手动推数后验证。
