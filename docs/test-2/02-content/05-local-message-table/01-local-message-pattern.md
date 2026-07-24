# 本地消息表模式

> `LocalMessage.java`（Entity） + `LocalMessageMapper.java`（7 个自定义 SQL） + `FeedMessageRetryJob.java`（2 个 @Scheduled）
> 解决的核心问题：在 @Transactional 内写入数据库之后，如何可靠地发送 MQ 消息。
> **前置阅读**：`04-transaction-aftercommit/` — 消息表的写入发生在事务内，MQ 的发送发生在 afterCommit 回调中；`06-cache-strategy/` — 同一个 afterCommit 还执行了延迟双删清除缓存。

---

## 1. 问题：为什么不能直接在事务里 asyncSend？

最直观的写法：

```java
@Transactional
public void publishNote(userId, request) {
    noteMapper.insert(note);              // DB 写入
    rocketMQTemplate.asyncSend(topic, msg); // MQ 发送
}
```

这会产生两类故障：

### 故障 A：DB 已提交，MQ 丢失

```
时间线：
  T1: INSERT INTO t_note → 写入成功（在事务内）
  T2: asyncSend → Broker 不可达（网络故障 / Broker 宕机）
  T3: commit → 事务提交成功 → 笔记入库了
  T4: asyncSend 的 callback → onException → 消息丢失

结果：笔记在 DB 里，但粉丝收不到 Feed 推送。永久不一致。
```

`@Transactional` 管不了 MQ。Spring 的事务管理器只知道 JDBC Connection，不知道 RocketMQ Producer。

### 故障 B：MQ 已发出，DB 回滚

```
时间线：
  T1: INSERT INTO t_note → 写入（在事务内，未提交）
  T2: asyncSend → Broker 收到消息 → 消费者开始处理
  T3: 消费者查 DB → 笔记不存在（事务未提交）
  T4: throw RuntimeException → 事务回滚 → 笔记没入库

结果：消费者空跑一趟，或者缓存了"笔记存在"的错误状态。
```

两个故障的根本原因相同：**DB 事务和 MQ 发送不是原子操作**。这不是 RocketMQ 的问题——任何消息队列都无法和 DB 事务共享提交语义（除非用 XA 事务，代价极高）。

---

## 2. 本地消息表的设计

### 2.1 核心思路

把"发 MQ"这件事从 "fire-and-forget" 变成 "先写本地表，再异步发"：

```
@Transactional 内：
  noteMapper.insert(note)              ← DB 操作 1
  localMessageMapper.insert(localMsg)  ← DB 操作 2（⚠️ 关键：同一事务）
  registerSynchronization(afterCommit {
      asyncSend MQ                     ← 事务提交后再发
  })
```

两笔 INSERT 共享一个 DB 事务——要么都成，要么都败。不存在"笔记入库但消息表没写"。

事务提交之后才 `asyncSend`——消费者查 DB 时一定能看到笔记。

### 2.2 状态机

```
                          ┌───────────┐
                          │ status=0  │ ← INSERT 时的初始状态
                          │ 待发送    │
                          └─────┬─────┘
                                │
                    afterCommit asyncSend
                                │
                    ┌───────────┴───────────┐
                    │                       │
                onSuccess               onException
                    │                       │
                    ▼                       ▼
            ┌─────────────┐        ┌──────────────┐
            │ status=1    │        │ status=0 保持 │ ← 等 FeedMessageRetryJob
            │ 已发送      │        │ retry_count++ │
            └─────────────┘        └──────┬───────┘
                                         │
                                retry >= 3 次？
                                         │
                            ┌────────────┴────────────┐
                            │                         │
                            ▼                         ▼
                    ┌──────────────┐         ┌──────────────┐
                    │ status=3     │         │ status=0 保持 │
                    │ 死信         │         │ 下一轮继续    │
                    └──────────────┘         └──────────────┘
```

但这不是全部——还有 Feed 推送的维度：

```
status=1（MQ 已发送）
  │
  ├─ push_status=0 → FeedPushConsumer 尚未开始推送
  ├─ push_status=1 → 推送中（断点续推）
  ├─ push_status=2 → 推送完成（终态）
  └─ push_status=3 → 推送失败（由 compensateIncompletePush 补偿）
```

**双状态机的意义**：`status` 跟踪 MQ 发送，`push_status` 跟踪 Feed 消费推送。两者独立但关联——只有 `status=1` 的记录才有意义的 `push_status`。

### 2.3 表结构

```sql
CREATE TABLE t_local_message (
    id          BIGINT PRIMARY KEY,           -- 雪花 ID
    topic       VARCHAR(64),                  -- MQ Topic
    body        TEXT,                         -- 消息体（JSON）
    status      TINYINT DEFAULT 0,            -- 0=待发送 1=已发送 2=失败 3=死信
    retry_count INT DEFAULT 0,               -- 重试次数
    push_status TINYINT,                      -- 0=未推 1=推送中 2=已推 3=失败
    push_cursor INT,                          -- 已推送到第几个粉丝
    push_total  INT,                          -- 总粉丝数
    created_at  DATETIME
);
INDEX idx_status_retry (status, retry_count, created_at)
INDEX idx_push_status (push_status)
```

`body` 存储 JSON 序列化的 `NotePublishEvent`——里面包含回放消息所需的全部信息（noteId、authorId、publishTime）。

为什么要存整条 JSON 而不是只存 noteId？因为补偿重试时需要完整的事件对象——如果只存 noteId，补偿时还需要再查一次 DB 构造事件，增加了额外查询和时序依赖。

---

## 3. 补偿机制

### 3.1 retryFailedMessages — MQ 发送补偿

```java
@Scheduled(fixedRate = 30000)   // 每 30 秒
public void retryFailedMessages() {
    LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(60); // 60 秒宽限期
    List<LocalMessage> messages = localMessageMapper.selectPending(
        cutoffTime,   // 只扫描创建 60 秒前的
        MAX_RETRY,    // 3 次上限
        SCAN_LIMIT    // 每次最多 100 条
    );

    for (LocalMessage msg : messages) {
        NotePublishEvent event = parseJson(msg.getBody());
        rocketMQTemplate.syncSend(topic, event, 3000);  // 同步发送（补偿不走异步）
        localMessageMapper.markSent(msg.getId());        // status 0→1
    }
}
```

**为什么 cutoffTime 是 60 秒？** 预留初次 `asyncSend` 的网络延迟 + callback 处理时间。如果消息刚创建 5 秒就被定时任务扫到并重试，而 `onSuccess` callback 随后又把 `status` 改成了 1——两个 UPDATE 同时跑，不会出错（都是 `SET status=1`，幂等），但会产生一次重复 MQ 发送。

**为什么补偿用 syncSend？** 这不是追求吞吐的场景——补偿的是少数失败消息。syncSend 能同步拿到发送结果，成功就标记，失败就重试计数+1。不需要再套一层 callback。

**incrementRetry 的死信逻辑**：

```java
@Update("UPDATE t_local_message SET retry_count = retry_count + 1, "
      + "status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 3 ELSE status END "
      + "WHERE id = #{id}")
int incrementRetry(@Param("id") Long id, @Param("maxRetry") int maxRetry);
```

注意 `retry_count + 1 >= maxRetry`，不是 `retry_count >= maxRetry`。原因是 SQL 中 `SET retry_count = retry_count + 1` 的赋值发生在 `CASE WHEN` 之前——所以判断时需要拿加 1 后的值来比较。

当 `retry_count=2, maxRetry=3`：`2 + 1 >= 3` → 死信。重试次数是 3（0→1→2→3，status 变为 3）。

### 3.2 compensateIncompletePush — Feed 推送补偿

```java
@Scheduled(fixedRate = 60000)   // 每 60 秒
public void compensateIncompletePush() {
    LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(120); // 120 秒宽限期
    List<LocalMessage> messages = localMessageMapper.selectPendingPushWithDelay(
        cutoffTime,
        PUSH_COMPENSATE_SCAN_LIMIT  // 每次最多 50 条
    );

    for (LocalMessage msg : messages) {
        NotePublishEvent event = parseJson(msg.getBody());
        event.setLocalMsgId(msg.getId());   // 关键：Consumer 断点恢复需要
        rocketMQTemplate.syncSend(topic, event, 3000);
        localMessageMapper.updatePushProgress(msg.getId(), 1, msg.getPushCursor());
    }
}
```

**这是什么场景？** `status=1`（MQ 已发送）但 `push_status IN (0, 1)`（Feed 推送未完成或中断）。说明 MQ 发成功了，但 `FeedPushConsumer` 没能完成推送——可能是 Consumer 在处理过程中崩溃了，推了一半粉丝就停了。

**断点续推**：Consumer 每批 Pipeline 完成后把 `cursor` 写入 Redis（`myxhs:feed:push:progress:{localMsgId}`）。重新投递 MQ 时，Consumer 从 Redis 读到上次的 `cursor`，接着推，不重头开始。ZADD 天然幂等——重复推送到已经收到过的粉丝，ZADD 只更新 score，不做重复插入。

**为什么 cutoffTime 是 120 秒？** 比 MQ 发送补偿的 60 秒更长——因为 Feed 推送可能涉及几千粉丝的 Pipeline 操作，单次推送本身就需要时间。120 秒是给 Consumer 预留的"合理处理时间"。

---

## 4. 完整性——为什么评论通知没有补偿？

`CommentService.createComment()` 发送的 `NOTIFICATION_TOPIC` 没有本地消息表保护：

```java
// CommentService.java afterCommit
rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC", notification, callback);
```

没有 `LocalMessage`，没有 `retryFailedMessages` 兜底。如果这次 `asyncSend` 失败——评论通知永久丢失。

对比：
| 特性 | 笔记发布 | 评论通知 |
|------|:--:|:--:|
| 本地消息表 | ✅ | ❌ |
| MQ 发送补偿 | ✅ retryFailedMessages 30s | ❌ |
| Feed 推送补偿 | ✅ compensateIncompletePush 60s | ❌ |
| 死信机制 | ✅ status=3 | ❌ |
| Trace 包装 | ✅ MqTraceHelper | ❌ |

**这是设计选择**——通知的可靠性要求低于 Feed 推送（Feed 关心"粉丝必须看到"，通知只是"告诉作者"。丢一条通知不影响数据一致性）。

---

## 5. 与其他方案的对比

### 5.1 事务消息（RocketMQ Transaction Message）

RocketMQ 原生支持事务消息——半消息 + 本地事务 + 回查：

```
1. 发送半消息（Half Message）→ Broker 暂存，不对消费者可见
2. 执行本地事务（INSERT t_note）
3. 根据本地事务结果 → COMMIT 或 ROLLBACK 半消息
4. COMMIT → 消息对消费者可见；ROLLBACK → Broker 删除半消息
5. 如果迟迟不确认 → Broker 回查 Producer 的 checkLocalTransaction()
```

**为什么这个项目没用事务消息？**

- 事务消息的回查接口需要实现 `checkLocalTransaction()`——回查时查什么？查 note 是否存在？如果回查接口不可用，消息永久停在一个半消息状态
- 事务消息只解决"发 vs 不发"的问题——但 Feed 推送的断点续推逻辑是额外需求，和事务消息无关
- 本地消息表方案更通用——不依赖特定 MQ 的能力，换 RabbitMQ/Kafka 也能用

**选择本地消息表而非事务消息是正确的**——因为 Feed 推送的可靠性不是"消息有没有发出"，而是"推送有没有完成"。需要的是双状态机（status + push_status），事务消息解决不了第二层。

### 5.2 Transactional Outbox（发件箱模式）

与本地消息表本质相同。区别在于发件箱模式通常和 **Debezium（CDC，Change Data Capture）** 配对：

```
INSERT INTO t_outbox (topic, payload, status)  ← 与业务表同一事务
Debezium 监听 MySQL binlog → 读到 outbox 变更 → 自动发到 Kafka/RabbitMQ
```

本项目的本地消息表是自己写定时任务扫表，发件箱模式用 Debezium 自动监听 binlog 发消息。前者多了定时扫表的开销，但不依赖额外的 CDC 组件。

### 5.3 Seata AT / TCC / XA

| 方案 | 对 MQ 发送的覆盖 | 代价 |
|------|:--:|------|
| XA 事务 | 包含 MQ（如果 MQ 支持 XA） | 两阶段提交，性能差，MQ 几乎不支持 |
| Seata AT | 不包含 MQ | 自动生成 undo_log，代理数据源 |
| Seata TCC | 不包含 MQ | 需要手写 Try/Confirm/Cancel |
| 本地消息表 | 不直接包含——但保证消息可恢复 | 需要定时扫表，额外 DB 写入 |

**结论**：分布式事务框架管的是 DB 之间的一致性（如 order + inventory + coupon）。MQ 不参与分布式事务——靠本地消息表保证"至少发一次"，消费者靠幂等保证"最多处理一次"。

---

## 6. 边界条件与可运维性

### 6.1 消息重复

定时任务和 `onSuccess` callback 可能同时更新同一条记录。但两个 UPDATE 都是 `SET status=1`——重复执行无副作用。

MQ 消息可能发送两次（定时任务重试时，前一次的 onSuccess 还没回调）。FeedPushConsumer 通过 ZADD 天然幂等（相同 noteId 的 ZADD 只更新 score，不重复插入）。

### 6.2 表增长

`t_local_message` 只增不删（即使推送完成也不物理删除）。长期运行后表会持续增长。

**改进方向**：定时归档 `status=1 AND push_status=2`（已发送且已推送完成）的记录到历史表，避免扫描 `selectPending` 时遍历已完成的行。当前索引 `idx_status_retry` 已有效过滤 status=0 的行，但表大小仍影响备份和迁移。

### 6.3 多实例并发

`retryFailedMessages` 在多实例环境下可能被多个实例同时触发。每个实例都会扫描 `status=0` 的行并尝试重试。

`markSent(id)` 是幂等的——多个实例同时调用，结果相同。但 `incrementRetry` 不是幂等的——两个实例各 +1，最终 retry_count 会跳 2（本应只跳 1）。这导致重试次数提前耗尽，消息过早标记为死信。

**理想方案**：用分布式锁（Redisson）保护扫描——但当前代码没有实现，算是已知局限。

### 6.4 死信监控

当前死信（status=3）没有告警或仪表盘。如果 `t_local_message` 里积累了大量死信，运维人员无从得知。

应加上：Prometheus Gauge 监控 `count(status=3)`，AlertManager 告警规则（死信 > 10 → P2 告警）。

---

## 7. 总结

本地消息表解决的是**非原子操作的一致性保障**问题：

```
DB 写入 + MQ 发送 ≠ 原子操作
  ↓
在 DB 事务内多写一张消息表 → 使 DB 写入和消息记录成为原子操作
  ↓
事务提交后从消息表读取并重发 → 借助定时任务弥补 MQ 发送的不可靠性
```

| 维度 | 本地消息表 | 事务消息 | 发件箱+CDC |
|------|:--:|:--:|:--:|
| 额外依赖 | 无 | RocketMQ 事务消息能力 | Debezium + Kafka |
| 回查机制 | 定时扫表 | checkLocalTransaction | binlog 实时 |
| 延迟 | 30s-60s（定时扫表周期） | <1s（回查） | <1s（实时 binlog） |
| 通用性 | 任意 DB + MQ | 仅 RocketMQ | 需要 MySQL binlog |
| 本项目的选择 | ✅ | — | — |

选择本地消息表是正确的——最低依赖、最高通用性、满足业务需求（Feed 推送不需要秒级实时，30s 延迟可接受）。
