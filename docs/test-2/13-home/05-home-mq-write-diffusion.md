# Home MQ 写扩散 + 断点续推 — 深度技术分析

> 关联源码：`FeedPushConsumer.java` / `FeedService.java` / `FeedCleanupJob.java`

---

## 业务背景

用户发布笔记后，粉丝的 Feed 流需要能刷到。两种模式：

```
推模式（写扩散）：发布时把笔记 ID 写入每个粉丝的收件箱
  → 粉丝读 Feed 时 O(1) 拿到结果
  → 大V 场景：100 万粉丝 = 100 万次写入 ❌

拉模式（读扩散）：发布时只写作者的发件箱
  → 粉丝读 Feed 时实时拉取关注人的发件箱
  → 每次读 Feed 聚合 N 个人的数据 ❌
```

Home 模块用**推拉混合**：普通用户推，大V 拉（阈值 10 万粉丝）。

---

## 推送链路

```
内容服务发布笔记
    ↓
RocketMQ FEED_TOPIC（NotePublishEvent：noteId/authorId/publishTime/localMsgId）
    ↓
FeedPushConsumer.onMessage()
    ├─ 判断是否大V（Redis bigv 标记缓存，10min TTL）
    ├─ 是 → ZADD myxhs:feed:outbox:{authorId}（1 次写入，拉模式）
    └─ 否 → pushToFollowers()（推模式，写扩散）
```

---

## 推模式：pushToFollowers

### 批量 Pipeline

```java
int batchSize = 500;
while (cursor < totalFollowers) {
    Set<String> followerIds = stringRedisTemplate.opsForZSet()
            .range(followerKey, cursor, cursor + batchSize - 1);

    // Pipeline：500 个粉丝的 ZADD + EXPIRE 一次网络往返
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

**优化**：500 个粉丝的写扩散从 500 次 Redis 往返 → 1 次 Pipeline。

### 为什么用 ZSet

| 数据结构 | 优点 | 缺点 |
|---|---|---|
| List | 简单 | 不能按时间排序、重复推送会重复 |
| Set | 天然去重 | 无序 |
| **ZSet** | 去重 + score 排序 | 内存略高 |

ZSet 的 score = 发布时间戳：
- 去重：同一 noteId 重复 ZADD 只更新 score
- 排序：Feed 流按 score 降序 = 按时间倒序
- 分页：ZREVRANGEBYSCORE 游标分页

---

## 断点续推

### 问题

```
10 万粉丝，500 条/批 = 200 批
推到第 100 批时 Consumer 崩溃
MQ 重试 → 从头推 → 浪费 50% 工作量
```

### 进度记录

```java
// 每批完成后记录游标
String progressKey = "myxhs:feed:push:progress:" + localMsgId;
stringRedisTemplate.opsForValue().set(progressKey, String.valueOf(cursor), Duration.ofHours(1));
```

### 断点恢复

```java
// 消费时检查是否有历史进度
long startCursor = 0;
String progressStr = stringRedisTemplate.opsForValue().get(progressKey);
if (progressStr != null) {
    startCursor = Long.parseLong(progressStr);  // 从中断处继续
}
```

### 幂等保障

```
ZADD 天然幂等：相同 member（noteId）重复添加只更新 score
→ 即使断点恢复时重复推送了部分粉丝，收件箱不会出现重复笔记
```

### 完成标记

```java
// 全部推送完成后
stringRedisTemplate.opsForHash().put(progressKey, "status", "completed");
stringRedisTemplate.expire(progressKey, Duration.ofHours(1));
```

---

## 大V 判断

```java
private boolean checkBigV(Long authorId) {
    String bigVKey = "myxhs:user:bigv:" + authorId;
    String cached = stringRedisTemplate.opsForValue().get(bigVKey);
    if (cached != null) return "1".equals(cached);  // 缓存命中

    // 缓存未命中 → ZCARD 粉丝数
    Long followerCount = stringRedisTemplate.opsForZSet().zCard(FOLLOW_FANS + authorId);
    boolean isBigV = followerCount != null && followerCount >= 100000;

    // 缓存 10 分钟
    stringRedisTemplate.opsForValue().set(bigVKey, isBigV ? "1" : "0", Duration.ofMinutes(10));
    return isBigV;
}
```

**10 分钟 TTL 的权衡**：
- 太长（1h）：用户跨过阈值时缓存不刷新，推模式写 10 万粉丝 → Redis 写入风暴
- 太短（0）：每次发笔记都 ZCARD → 增加 Redis 负担
- 10 分钟：可接受的不一致窗口

---

## 失败处理

| 场景 | 行为 |
|---|---|
| MQ 消费异常 | 抛 RuntimeException → MQ 重试（maxReconsumeTimes=3） |
| 重试 3 次仍失败 | 进入死信队列（%DLQ%feed-push-consumer-group） |
| 推送中途崩溃 | 断点续推（progress 游标恢复） |
| 大V 判断缓存未命中 | ZCARD 实时查询 + 回填缓存 |

---

## 面试 Q&A

**Q: 为什么推模式要分批（500/批）而不是一次性 ZADD？**
A: 一次性 ZADD 10 万条会阻塞 Redis 主线程数秒（Redis 单线程）。分批 Pipeline（500/批）单次执行毫秒级，且批间可以记录进度支持断点续推。

**Q: 断点续推的 progress key 为什么 TTL 1 小时？**
A: MQ 重试的窗口通常 < 1 小时。超过 1 小时说明消息大概率不会再被重试，进度信息无意义，自动过期释放内存。

**Q: 大V 阈值跨过时（9.9万→10.1万）会怎样？**
A: 缓存 10 分钟内仍标记"非大V"→ 新笔记走推模式写入 10.1 万粉丝 → Redis 压力大但不产生数据错误（粉丝能收到）。10 分钟后缓存刷新为"大V"，后续笔记走拉模式。

**Q: 推送进度和 MQ 消费是原子的吗？**
A: 不是严格原子。崩溃窗口内可能"进度已写但消息未提交"或反之。但因为 ZADD 幂等 + 进度只影响从哪继续（不重推就漏推，重复推无害），最终一致。

---

## 生产实验

### Redis 收件箱数据实测（2026-07-30）

```
myxhs:feed:inbox:10001 → 7 条（ZSet，score=发布时间戳）
myxhs:feed:outbox:*     → 0 条（无大V发过笔记）
```

Feed 流读取验证：`GET /api/home/feed` 返回收件箱中的 7 条笔记（部分笔记在 content 服务不存在被聚合过滤）。

### 未实测

- RocketMQ FEED_TOPIC 消费（需内容服务发布笔记事件）
- 断点续推（需模拟 Consumer 崩溃 + MQ 重试）
- 大V 拉模式路径（需粉丝数 ≥ 10 万的账号）

---

## 发散

### 推模式变体：扇出到分组

当前每个粉丝一个收件箱 Key（`inbox:{userId}`），10 万粉丝 = 10 万 Key。优化方案：按分组扇出（如 1000 人一组，组内共享一个 ZSet），减少 Key 数量。牺牲的是"单人清空收件箱"的复杂度。

### 大V 列表缓存预热

粉丝数接近阈值的用户（如 8-12 万）提前预热 bigv 缓存，减少阈值跨越时的瞬时压力。

### 慢粉丝（离线用户）处理

当前离线粉丝同样写入收件箱（7 天 TTL）。如果离线超过 7 天，笔记在收件箱中过期——重新上线后 Feed 会缺这段时间的内容。可以考虑离线用户不写收件箱，上线时拉取（混合模式进一步细化）。
