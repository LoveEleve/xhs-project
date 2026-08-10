# 本地消息表 + MQ 可靠性保障

> **源码**: LocalMessage(50行) + FeedMessageRetryJob(147行) + LocalMessageMapper(82行)  
> **模式**: Transactional Outbox / 本地消息表  
> **补偿**: 双定时任务 + Redis 断点续推

---

## 1. 问题定义：为什么不能直接在事务里发 MQ？

```java
@Transactional
public Long publishNote() {
    noteMapper.insert(note);           // 1. INSERT 笔记
    rocketMQTemplate.syncSend(...);   // 2. 发 MQ — 如果这里失败？
}
// 事务回滚？已经发了！→ 粉丝看到幻影笔记
```

**核心矛盾**：数据库事务和 MQ 发送是两个独立的系统，无法做分布式事务（除非用 RocketMQ 事务消息，但复杂度高）。

**解决方案**：本地消息表 —— 在同一个 MySQL 事务里插入一条"待发送"记录，事务提交后异步发 MQ。

```
┌────── 同一个 @Transactional ───────┐
│  INSERT t_note          ✓           │
│  INSERT t_local_message ✓  (status=0)│
└──── COMMIT ────────────────────────┘
     │
     ▼
afterCommit() → asyncSend MQ → 成功 → markSent(status=1)
                             → 失败 → FeedMessageRetryJob 重试
```

---

## 2. 数据模型：LocalMessage

```java
// LocalMessage.java:17-49
@TableName("t_local_message")
public class LocalMessage {
    @TableId(type = IdType.ASSIGN_ID)   // 雪花 ID
    private Long id;

    private String topic;               // MQ Topic（固定 "FEED_TOPIC"）
    private String body;                // JSON 序列化的 NotePublishEvent
    private Integer status;             // 0=待发送 1=已发送 2=发送失败 3=死信
    private Integer retryCount;         // 当前重试次数
    private LocalDateTime createdAt;

    // === Feed 推送进度跟踪 ===
    private Integer pushStatus;         // 0=未推送 1=推送中 2=已推送 3=推送失败
    private Integer pushCursor;         // 已推送到的粉丝游标
    private Integer pushTotal;          // 总粉丝数
}
```

### 状态机

```
  INSERT
    │
    ▼
  status=0 (待发送) ──────────────────┐
    │                                  │
    ▼ (MQ 发送成功, afterCommit 回调)   │ (MQ 发送失败, 定时任务扫描)
  status=1 (已发送)                     │
    │                                  │
    ▼ (FeedPushConsumer 推送完成)        │ 重试 3 次仍失败
  push_status=2 (已推送)                │
    │                                  ▼
    │                           status=3 (死信)
    │                              △ 不再重试，需人工介入
```

---

## 3. 补偿任务 1：MQ 发送失败重试

```java
// FeedMessageRetryJob.java:51-83
@Scheduled(fixedRate = 30000)    // 每 30 秒
public void retryFailedMessages() {
    // 扫描 status=0 且 retry_count < MAX_RETRY 且距创建超过 60s 的消息
    LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(RETRY_DELAY_SECONDS);
    List<LocalMessage> messages = localMessageMapper.selectPending(cutoffTime, MAX_RETRY, SCAN_LIMIT);

    for (LocalMessage msg : messages) {
        try {
            NotePublishEvent event = objectMapper.readValue(msg.getBody(), NotePublishEvent.class);
            rocketMQTemplate.syncSend(msg.getTopic(), event, 3000);  // 同步 3s 超时
            localMessageMapper.markSent(msg.getId());                // 标记已发送
        } catch (Exception e) {
            localMessageMapper.incrementRetry(msg.getId(), MAX_RETRY);  // 重试+1，超限标记死信
        }
    }
}
```

### 为什么 `syncSend` 不是 `asyncSend`？

在补偿场景中，如果 `asyncSend` 失败，回调无法确定——因为这是在定时任务线程中执行的，不是请求线程。`syncSend` 会阻塞等待 Broker 确认，返回 `SendStatus`，明确知道成功还是失败。

### 重试上限与死信

```sql
-- LocalMessageMapper.java:40-43
UPDATE t_local_message SET retry_count = retry_count + 1,
    status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 3 ELSE status END
WHERE id = #{id}
```

| 当前 retry_count | 执行后 retry_count | retry_count + 1 >= 3? | status |
|:--:|:--:|:--:|:--:|
| 0 | 1 | 1 >= 3 ❌ | 保持 0（待发送） |
| 1 | 2 | 2 >= 3 ❌ | 保持 0（待发送） |
| 2 | 3 | 3 >= 3 ✅ | → 3（死信） |

**第 3 次失败后标记死信**，不再扫描。需要人工介入或监控告警。

---

## 4. 补偿任务 2：Feed 推送未完成补偿

```java
// FeedMessageRetryJob.java:97-146
@Scheduled(fixedRate = 60000)    // 每 60 秒
public void compensateIncompletePush() {
    // 扫描 status=1(MQ已发送) 且 push_status ∈ (0,1)(推送未完成) 的消息
    LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(PUSH_COMPENSATE_DELAY_SECONDS);
    List<LocalMessage> messages = localMessageMapper.selectPendingPushWithDelay(cutoffTime, PUSH_COMPENSATE_SCAN_LIMIT);

    for (LocalMessage msg : messages) {
        // 0. 检查 Redis 进度：已完成的消息跳过
        String progressKey = PUSH_PROGRESS_PREFIX + msg.getId();
        Object status = stringRedisTemplate.opsForHash().get(progressKey, "status");
        if ("completed".equals(status)) {
            localMessageMapper.updatePushProgress(msg.getId(), 2, lastCursor);
            continue;  // ← 跳过已完成
        }

        // 1. 重新投递到 MQ
        NotePublishEvent event = objectMapper.readValue(msg.getBody(), NotePublishEvent.class);
        if (event.getLocalMsgId() == null) event.setLocalMsgId(msg.getId());
        rocketMQTemplate.syncSend(msg.getTopic(), event, 3000);

        // 2. 标记推送中
        localMessageMapper.updatePushProgress(msg.getId(), 1, lastCursor);
    }
}
```

### 断点续推机制

```
首次推送: FeedPushConsumer 收到消息
    → 分批 Pipeline ZADD 到粉丝 Timeline
    → 每批完成后 HSET feed:push:progress:{localMsgId} cursor N

Consumer 崩溃:
    → push_status 仍为 0 或 1
    → 60s 后 compensateIncompletePush 扫描到
    → 重新 syncSend MQ
    → Consumer 从 Redis 读取 cursor 继续推送

最终完成:
    → HSET feed:push:progress:{localMsgId} status "completed"
    → updatePushProgress(msg.getId(), 2, cursor)
```

---

## 5. Mapper 设计细节

### `selectPending` — 延迟过滤避免误判

```sql
SELECT * FROM t_local_message
WHERE status = 0 AND retry_count < #{maxRetry}
  AND created_at < #{cutoffTime}     ← 关键：60 秒前创建的消息才扫描
ORDER BY id ASC LIMIT #{limit}
```

**为什么需要 `created_at < cutoffTime`？**

如果刚创建的消息（0 秒前）立即被扫描，afterCommit 回调可能还没执行——导致"消息已发送但状态未更新"的误判重试。60 秒延迟给了 asyncSend 回调足够的时间（正常 <100ms）。

### `incrementRetry` — CASE 表达式防 off-by-one

```sql
UPDATE t_local_message SET retry_count = retry_count + 1,
    status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 3 ELSE status END
WHERE id = #{id}
```

**为什么用 `retry_count + 1` 而不是 `retry_count`？**

MySQL UPDATE 中 SET 子句按从左到右执行。`retry_count` 先被更新为 +1，然后再用新值判断 `>= maxRetry`。用 `retry_count + 1` 相当于：如果旧值是 2，新值是 3，新值 = maxRetry → 标记死信。

如果用 `retry_count` 本身（旧值），retry_count=2 < maxRetry=3 → 不会标记死信 → 下轮扫描又重试 → 无限循环。

---

## 6. 完整补偿时序

```
T+0    事务 COMMIT，afterCommit → asyncSend MQ
T+0.1  MQ Broker 确认收到 → callback.markSent(status=1)
T+0.1  FeedPushConsumer 消费 → 推送粉丝 Timeline

[如果发生故障...]

T+30   retryFailedMessages 第1次扫描
       → status=0 的消息重新 syncSend
       → 成功 → markSent; 失败 → retry_count=1

T+60   retryFailedMessages 第2次扫描 + compensateIncompletePush 第1次
       → status=0 且 retry_count=1 的再试 → retry_count=2
       → status=1 但 push_status=0 的补偿推送

T+90   retryFailedMessages 第3次扫描 → retry_count=3 → 死信

T+120  compensateIncompletePush 第2次 → 再检查 push_status
```

**关键常数**：

| 常数 | 值 | 含义 |
|------|:--:|------|
| RETRY_DELAY_SECONDS | 60 | 创建 60s 后才开始扫描 |
| MAX_RETRY | 3 | 最多重试 3 次 |
| PUSH_COMPENSATE_DELAY_SECONDS | 120 | 创建 120s 后才补偿推送 |
| SCAN_LIMIT | 100 | 每轮最多扫描 100 条 |

---

## 7. 故障场景

| 场景 | 处理路径 | 恢复时间 |
|------|---------|:--:|
| MQ Broker 短暂不可用 | retryFailedMessages 30s 后重试 | ~90s |
| FeedPushConsumer 崩溃 | compensateIncompletePush 60s 后补偿 | ~180s |
| asyncSend 回调丢消息 | retryFailedMessages 60s 后扫描 status=0 | ~90s |
| 3 次重试全部失败 | 死信（status=3），需人工介入 | ∞ |
| Redis 推送进度丢失 | Consumer 从头推送（幂等 ZADD） | 数据冗余但安全 |

### 发现与修复（2026-08-04 deep review）

| # | 严重 | 问题 | 修复 |
|---|:--:|------|------|
| 1 | 🔴 | `retryFailedMessages`/`compensateIncompletePush` 无分布式锁，多实例并发导致双发 MQ | 加 Redis SETNX 锁（TTL=25s/55s） |
| 2 | 🟡 | `markSent` 无条件 UPDATE `WHERE id=?` — 多实例可能重复标记 | 加 `AND status=0` 乐观锁 |
| 3 | 🟡 | `syncSend` 3s 超时抛异常但 Broker 可能已收到 — 重试导致重复消息 | 记录，依赖 Consumer 幂等（ZADD） |
| 4 | 🟢 | 文档遗漏 `created_at < cutoffTime` 60s 延迟保护 afterCommit 误扫描 | 已补充说明 |

---

## 8. 知识点索引

| 知识点 | 源码 | 行号 |
|--------|------|------|
| LocalMessage 实体 | `LocalMessage.java` | 17-49 |
| 初始写入（与笔记同事务） | `NoteService.java` | 103-109 |
| afterCommit 回调标记 | `NoteService.java` | 132-136 |
| retryFailedMessages | `FeedMessageRetryJob.java` | 51-83 |
| compensateIncompletePush | `FeedMessageRetryJob.java` | 97-146 |
| selectPending SQL | `LocalMessageMapper.java` | 21-27 |
| incrementRetry CASE | `LocalMessageMapper.java` | 40-43 |
| selectPendingPushWithDelay | `LocalMessageMapper.java` | 59-64 |

---

## 9. 面试要点

**Q1**: 本地消息表 vs RocketMQ 事务消息，选哪个？

**A**: 
- **本地消息表**：简单可靠，不需要 RocketMQ 特定版本。缺点是消息和业务耦合在同一个数据库。适合单体/少量 Topic 场景。
- **RocketMQ 事务消息**：两阶段提交（half message → executeLocalTransaction → commit/rollback），不需要本地消息表。缺点是需要 RocketMQ 4.3+ 版本，且本地事务执行器需要幂等。
- my-xhs 选择了本地消息表——因为笔记发布对延迟敏感，消息表插入在同一个事务内，零额外网络开销。

**Q2**: `incrementRetry` 为什么用 `CASE WHEN retry_count + 1 >= #{maxRetry}` 而不是先 UPDATE 再判断？

**A**: 如果分成两步（先 UPDATE retry_count，再 SELECT 判断），之间有并发窗口——另一个线程可能也在扫描同一条消息。一步完成的 CASE 表达式在同一个 SQL 原子操作中完成了"递增 + 判定"。

**Q3（陷阱）**: `selectPending` 中 `created_at < cutoffTime` 如果写成了 `created_at <= cutoffTime` 有什么问题？

**A**: 功能上没有区别，但 `<=` 会多扫描边界时间点到 to 时间创建的记录，这些记录可能还没到 afterCommit 回调执行。`<` 给了至少 1 秒的缓冲窗口，更保守。但真正的保护是 60 秒的 DELAY，1 秒的差异在 60 秒面前可以忽略。

---

*下一篇：笔记生命周期 — 状态机转换*
