# MQ 异步发送 + 定时补偿

> `NoteService.publishNote()` 的 `afterCommit` 回调（`NoteService.java:126-145`）+ `FeedMessageRetryJob.java`（2 个 @Scheduled）
> 核心问题：`asyncSend` 不等待 Broker 确认就返回——消息可能丢失。定时任务负责兜底。
> **前置阅读**：`04-transaction-aftercommit/` — asyncSend 在 afterCommit 中执行；`05-local-message-table/` — 补偿任务依赖本地消息表的状态机。

---

## 1. asyncSend vs syncSend：两种发 MQ 的方式

```java
// publishNote 用了这个
rocketMQTemplate.asyncSend("FEED_TOPIC", event, new SendCallback() {...});

// FeedMessageRetryJob 用了这个
rocketMQTemplate.syncSend("FEED_TOPIC", event, 3000);
```

| 维度 | asyncSend | syncSend |
|------|-----------|---------|
| 返回时机 | 立即返回（~1ms），不等待 Broker 确认 | 等 Broker 返回确认结果后返回（~50ms） |
| 成功确认 | 通过 `SendCallback.onSuccess` 异步通知 | 方法正常返回 = 成功 |
| 失败处理 | 通过 `SendCallback.onException` 异步通知 | 抛异常 |
| 调用线程阻塞 | 否 | 是 |
| 适用场景 | 追求低延迟的用户交互 | 补偿/兜底任务，可靠性优先 |

### 1.1 为什么 publishNote 用 asyncSend？

用户的请求等待时间 = 发布笔记的整个流程延迟。如果把 `asyncSend` 换成 `syncSend`：

```
当前：return note.getId() → 10ms 用户感知延迟
改为 syncSend：return note.getId() → 60ms 用户感知延迟（多了 MQ 往返 50ms）
```

多等 50ms 对单个用户不明显，但 QPS 100 时，Tomcat 线程池 150 个线程，每条请求多占 50ms = 等效减少 ~5 个可用线程。asyncSend 把 MQ 的延迟从关键路径上完全削掉了。

### 1.2 为什么 FeedMessageRetryJob 用 syncSend？

补偿任务不是用户交互——它跑在 @Scheduled 线程上，不关心延迟，只关心成功率。syncSend 同步拿到发送结果，成功就 markSent，失败就 incrementRetry，逻辑简单可靠。不需要再套一层 callback。

---

## 2. SendCallback 生命周期

```java
rocketMQTemplate.asyncSend("FEED_TOPIC", event, new SendCallback() {
    @Override
    public void onSuccess(SendResult sendResult) {
        // Broker 确认收到消息 → 标记本地消息已发送
        localMessageMapper.markSent(localMsgId);  // status: 0 → 1
    }

    @Override
    public void onException(Throwable e) {
        // 发送失败 → 只打日志，不处理
        // 由 FeedMessageRetryJob.retryFailedMessages() 补偿
        log.error("Feed推送失败(补偿任务重试), noteId={}", finalNoteId, e);
    }
});
```

### 2.1 onSuccess 做了什么？

`markSent(id)` = `UPDATE t_local_message SET status = 1 WHERE id = ?`。把消息状态从 0（待发送）改为 1（已发送）。这意味着什么？

- `retryFailedMessages` 扫描的是 `status = 0` 的行 → 这条不再被扫描
- `compensateIncompletePush` 扫描的是 `status = 1 AND push_status IN (0, 1)` → 下一步由这个任务接管

### 2.2 onException 不处理——为什么？

因为在 `afterCommit` 回调里写复杂的重试逻辑不合适：

- 网络抖动：回调线程可能在几秒后因为超时才收到 onException
- 线程池耗尽：如果 onException 里 syncSend 重试并阻塞，可能耗尽 afterCommit 线程池
- 逻辑分散：重试逻辑应该在 FeedMessageRetryJob 里统一管理，而不是散布在回调里

**设计原则**：异步回调只做轻量操作（markSent），失败不重试——由定时任务统一兜底。

### 2.3 竞态：onSuccess 和 retryFailedMessages 同时更新

```
T=30s: retryFailedMessages 扫描到 status=0，syncSend → 发送中
T=30.1s: afterCommit 的 onSuccess 回调到达 → markSent → UPDATE status=1
T=30.2s: retryFailedMessages 的 syncSend 完成 → markSent → UPDATE status=1
```

两个 UPDATE 都是 `SET status = 1`——**幂等的**。谁先执行谁后执行结果相同。唯一副作用是 MQ 可能发出了两次（callback 的 asyncSend + 定时任务的 syncSend）。但 `FeedPushConsumer` 使用 ZADD 天然幂等——相同 noteId 的 ZADD 只更新 score，不创建重复条目。

---

## 3. retryFailedMessages — MQ 发送补偿

```java
@Scheduled(fixedRate = 30000)   // 每 30 秒
public void retryFailedMessages() {
    LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(60);  // 60 秒宽限期
    List<LocalMessage> messages = localMessageMapper.selectPending(
        cutoffTime,   // status=0 AND created_at < now-60s AND retry_count < 3
        MAX_RETRY,    // 3
        SCAN_LIMIT    // 100
    );

    for (LocalMessage msg : messages) {
        NotePublishEvent event = parseJson(msg.getBody());
        rocketMQTemplate.syncSend(msg.getTopic(), event, 3000);
        localMessageMapper.markSent(msg.getId());   // 成功 → status=1
        // 失败 → incrementRetry → 3 次后死信
    }
}
```

### 3.1 为什么 cutoffTime 是 now-60s？

如果 `cutoffTime = now`，刚创建 1 秒的消息也可能被扫到——而此时 afterCommit 的 asyncSend 正在发送。定时任务再 syncSend 一次 → 重复发送。

60 秒的宽限期保证：只有在 afterCommit 执行 60 秒后 status 还保持 0 的消息，才认为"异步发送确定失败了"，才开始重试。

### 3.2 重试上限 = 3 次

```
第 1 次：afterCommit asyncSend → 失败（onException 不处理）
第 2 次：retryFailedMessages syncSend → 失败 → incrementRetry → retry_count=1
第 3 次：retryFailedMessages syncSend → 失败 → incrementRetry → retry_count=2
第 4 次：retryFailedMessages syncSend → 失败 → incrementRetry → retry_count+1(3) >= maxRetry(3)
          → status=3（死信）
```

死信意味着系统已经放弃了自动恢复，需要人工介入：检查 Broker 状态、检查消费端问题。

### 3.3 多实例并发问题

如果两个 content 实例同时运行 `retryFailedMessages`，可能同时扫描到同一批 status=0 的消息：

```
实例 A: syncSend → markSent → status=1
实例 B: syncSend → markSent → status=1（已经是 1，UPDATE 无副作用）
         → 但 MQ 消息被发了两次！
```

`FeedPushConsumer` 的 ZADD 天然幂等保证了重复消息无副作用。

---

## 4. compensateIncompletePush — Feed 推送补偿

```java
@Scheduled(fixedRate = 60000)   // 每 60 秒
public void compensateIncompletePush() {
    LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(120);  // 120 秒宽限期
    List<LocalMessage> messages = localMessageMapper.selectPendingPushWithDelay(
        cutoffTime,                  // status=1 AND push_status IN (0,1) AND created_at < now-120s
        PUSH_COMPENSATE_SCAN_LIMIT   // 50
    );

    for (LocalMessage msg : messages) {
        NotePublishEvent event = parseJson(msg.getBody());
        event.setLocalMsgId(msg.getId());  // Consumer 断点续推需要
        rocketMQTemplate.syncSend(msg.getTopic(), event, 3000);
        localMessageMapper.updatePushProgress(id, 1, cursor);  // push_status=1
    }
}
```

### 4.1 什么场景需要这个补偿？

正常流程：
```
afterCommit asyncSend → Broker → FeedPushConsumer 消费 → Pipeline ZADD 粉丝收件箱
                                                           ├─ 每 500 人一批
                                                           └─ 每批完成写 cursor 到 Redis
```

如果 FeedPushConsumer 在处理过程中崩溃：
```
Broker → FeedPushConsumer → 推了 200/1000 个粉丝 → 崩溃
                                    ↑ cursor=200 存在 Redis 里
```

此时 `t_local_message` 的状态是 `status=1, push_status=1, push_cursor=200`。普通的重试（retryFailedMessages）不会管它——因为 status 已经是 1 了。

`compensateIncompletePush` 就是为这个场景设计的：扫描 `status=1 AND push_status IN (0,1)` 的消息，重新投递到 MQ。FeedPushConsumer 从 Redis 读到上次的 cursor=200，接着推第 201 个粉丝，不重头开始。

### 4.2 为什么 cutoffTime 是 120s？

120 秒比消息发送补偿的 60 秒更长——因为 Feed 推送本身可能涉及几千粉丝的 Pipeline 操作，正常推送就需要数十秒。给 Consumer 足够的处理时间后，才判断"推送中断了"。

---

## 5. 完整可靠性链路

```
publishNote
  │
  ├─ @Transactional: INSERT t_note + INSERT t_local_message(status=0)
  │
  ├─ afterCommit:
  │     ├─ asyncSend FEED_TOPIC + SendCallback.onSuccess → markSent (status 0→1)
  │     └─ 如果 onException → 等 retryFailedMessages
  │
  ├─ [30s 后] retryFailedMessages 扫描 status=0, created_at < now-60s
  │     ├─ syncSend → markSent (status 0→1)
  │     └─ 3 次后 status=3（死信）→ 人工介入
  │
  ├─ FeedPushConsumer 消费 FEED_TOPIC
  │     ├─ 大 V（≥10 万粉丝）→ ZADD 作者发件箱（拉模式）
  │     ├─ 普通用户 → Pipeline ZADD 粉丝收件箱（推模式）
  │     │     └─ 每批 500 人 → 写 cursor 到 Redis
  │     ├─ 全部完成 → updatePushProgress(status=2) → 终态
  │     └─ 中途崩溃 → push_status=1, cursor=中途值
  │
  └─ [60s 后] compensateIncompletePush 扫描 push_status IN (0,1), created_at < now-120s
        ├─ 重新投递 MQ → Consumer 读 Redis cursor → 断点续推
        └─ updatePushProgress(cursor=0, status=1)
```

---

## 6. 为什么不用 RocketMQ 事务消息？

RocketMQ 原生提供事务消息（Half Message + checkLocalTransaction + commit/rollback）：

```
1. 发送半消息 → Broker 暂存
2. 执行本地事务（publishNote）
3. commit → 半消息转正式；rollback → 删除半消息
4. 如果迟迟不确认 → Broker 回查 checkLocalTransaction()
```

**为什么没用？**

| 维度 | 事务消息 | 本地消息表 |
|------|:--:|:--:|
| 需要实现回查接口 | 是（checkLocalTransaction） | 否 |
| 回查失效 | 半消息永久挂起 | 不存在这个问题（status=0 在 DB 里，可无限重试） |
| Feed 推送进度追踪 | 不支持 | 支持（push_status + push_cursor） |
| 通用性 | 仅 RocketMQ | 任意 MQ |

本地消息表不仅覆盖"消息是否发出"，还管理了"消息被消费后是否推送完成"——双状态机（status + push_status）。事务消息只能解决第一层。

---

## 7. 已知局限

| 局限 | 说明 |
|------|------|
| Broker 宕机空白窗口 | asyncSend 超时 ~3s，retryFailedMessages 延迟 60s，中间 ~57s 消息对消费者不可见——Feed 推送最多延迟 90s |
| 死信无监控 | status=3 的消息无 Prometheus 告警，积累到一定量才能被人工发现 |
| 多实例并发 | `retryFailedMessages` 多实例同时扫表 → 重复 MQ 发送 → ZADD 幂等兜底 |

## 总结

| 机制 | 触发时机 | 解决的问题 |
|------|---------|-----------|
| asyncSend + SendCallback | afterCommit | 不阻塞用户请求，异步发送 MQ |
| retryFailedMessages | @Scheduled(fixedRate=30000) | asyncSend 失败后的补偿（最多 3 次） |
| compensateIncompletePush | @Scheduled(fixedRate=60000) | Feed 推送中断的补偿（断点续推） |
| 死信（status=3） | 3 次重试后 | 人工介入的兜底 |
| ZADD 幂等 | Consumer 每次写入 | 防止重复消息产生副作用 |
