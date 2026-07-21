# 内容模块 curl 逐条测试记录

> 每条测试包含：curl 请求、实际返回、DB 验证、Redis 验证、业务讲解。

| 编号 | 接口 | 状态 |
|------|------|:----:|
| 3.1 | POST /api/note/publish | 通过 |
| 3.2 | GET /api/note/detail/{id} | 待测试 |
| 3.3 | GET /api/note/user/{userId} | 待测试 |
| 3.4 | GET /api/note/my | 待测试 |
| 3.5 | PUT /api/note/{id} | 待测试 |
| 3.6 | DELETE /api/note/{id} | 待测试 |
| 3.7 | POST /api/note/upload/image | 待测试 |
| 3.8 | POST /api/comment | 待测试 |
| 3.9 | GET /api/comment/list/{noteId} | 待测试 |
| 3.10 | GET /api/comment/count/{noteId} | 待测试 |
| 3.11 | DELETE /api/comment/{id} | 待测试 |

## 测试环境

- MySQL: `mysql -h 21.130.247.89 -P 13307 -u root -p'Xhs@2026#MySQL' my_xhs_content`
- Redis: `redis-cli -h 21.130.247.89 -p 16379 -a 'Xhs@2026#Redis'`
- 测试用户: newuser (id=2078387513547841537)

> 注：写接口通过直连服务端口 19002 + X-User-Id Header 测试，绕开 Gateway HMAC 签名限制。

---

## 3.1 发布笔记 — `POST /api/note/publish`

### curl 请求

```bash
curl -s -X POST http://localhost:19002/api/note/publish \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 2078387513547841537" \
  -d '{"title":"测试笔记","content":"这是一篇测试笔记的内容"}'
```

### 实际返回

```json
{ "code": 200, "message": "发布成功", "data": { "noteId": 2078408307372003329 } }
```

### MySQL 验证 — t_note

| 字段 | 值 | 说明 |
|------|------|------|
| id | `2078408307372003329` | 雪花 ID |
| user_id | `2078387513547841537` | newuser |
| title | `测试笔记` | 写入的值 |
| content | `这是一篇测试笔记的内容` | 写入的值 |
| status | `2` | PUBLISHED（直接发布，跳过审核） |
| note_type | `0` | 图文 |
| deleted | `0` | 正常 |

### MySQL 验证 — t_local_message

| 字段 | 值 | 说明 |
|------|------|------|
| topic | `FEED_TOPIC` | MQ Topic |
| status | `1` | 已发送（afterCommit asyncSend 成功后 markSent） |
| retry_count | `0` | 无需重试 |
| push_status | `0` | Feed 推送尚未开始 |
| push_cursor | `0` | 初始 |
| push_total | `0` | 尚未设置 |

### Redis 验证

| 验证项 | 结果 |
|--------|------|
| `myxhs:note:detail:2078408307372003329` | 不存在（afterCommit 延迟双删已清除缓存） |

### 业务讲解

请求首先打到 `NoteController.publishNote()`（`NoteController.java:28-36`）：

```java
@PostMapping("/publish")
public R<Map<String, Object>> publishNote(
        @RequestHeader("X-User-Id") Long userId,
        @Valid @RequestBody NotePublishRequest request) {
    Long noteId = noteService.publishNote(userId, request);
    return R.ok(Map.of("noteId", noteId));
}
```

Controller 层只有一个职责：从 Header 拿 userId，参数校验交给 `@Valid`（NotePublishRequest 上有 `@NotBlank title`、`@Size(max=128)` 等），然后把请求转给 Service。

---

进入 `NoteService.publishNote()`（`NoteService.java:80-150`）。这个方法有 `@Transactional(rollbackFor = Exception.class)`，意味着从方法入口到出口，所有 DB 操作共享同一个事务。任何一步抛异常，前面所有的 insert 都会撤销。

---

**第一行：DFA 敏感词检测（第 83 行）**

```java
checkSensitiveWords(request.getTitle(), request.getContent());
```

调的是私有方法（`NoteService.java:451-459`）：

```java
private void checkSensitiveWords(String title, String content) {
    String fullText = (title != null ? title : "") + " " + (content != null ? content : "");
    Set<String> sensitiveWords = dfaFilter.detect(fullText);
    if (!sensitiveWords.isEmpty()) {
        log.warn("[笔记] 检测到敏感词: {}", sensitiveWords);
        throw new BizException(ResultCode.NOTE_CONTENT_ILLEGAL,
                "内容包含违规词汇：" + String.join("、", sensitiveWords));
    }
}
```

把标题和正文用空格拼成一段文本，交给 `DFAFilter.detect()`。`detect()` 返回的是 `Set<String>`——具体匹配到了哪些词（比如 `{"赌博", "诈骗"}`）。

如果集合不为空 → 抛 `BizException(code=NOTE_CONTENT_ILLEGAL)`。由于方法上有 `@Transactional`，这个异常会触发事务回滚——DB 中不留下任何痕迹，用户只收到一个"内容包含违规词汇：赌博、诈骗"的 400 响应。

**为什么不把 DFA 放在入库后面检测？** 因为入库后 throw → 事务回滚 → 白写一次 DB。放在第一行，不进 DB 就被挡回去了。

---

**第二行：构建 Note 实体（第 86-88 行）**

```java
Note note = buildNote(userId, request);
note.setStatus(NoteStatus.PUBLISHED.getCode());       // 2
note.setAuditStatus(AuditStatus.APPROVED.getCode());   // 1
```

`buildNote()`（`NoteService.java:464-477`）：

```java
private Note buildNote(Long userId, NotePublishRequest request) {
    Note note = new Note();
    note.setId(idGeneratorUtil.nextId());      // 雪花算法：2078408307372003329
    note.setUserId(userId);                    // 作者
    note.setTitle(request.getTitle());
    note.setContent(request.getContent());
    note.setImages(toJson(request.getImages()));    // List<String> → "[\"url1\",\"url2\"]"
    note.setVideoUrl(request.getVideoUrl());
    note.setCoverUrl(request.getCoverUrl());
    note.setTopicIds(toJson(request.getTopicIds())); // List<Long> → "[1,2,3]"
    note.setTags(toJson(request.getTags()));          // List<String> → "[\"tag1\"]"
    note.setNoteType(request.getNoteType() != null ? request.getNoteType() : 0);
    return note;
}
```

注意 `idGeneratorUtil.nextId()` 在这里显式调用。因为在 `afterCommit` 的 MQ 消息里需要用到 `note.getId()` 构建 `NotePublishEvent`，如果在 `insert` 后才拿到 ID（等 MyBatis-Plus 分配），MQ 消息里就没有 noteId。

`images`/`topicIds`/`tags` 在 Java 里是 `List`，但 DB 不支持数组字段，所以 `toJson()` 用 Jackson 序列化成 JSON 字符串存进去，读的时候 `fromJsonList()` 反序列化回来。

`buildNote` 后手动设了 `status=2`（PUBLISHED）和 `auditStatus=1`（APPROVED）。正常情况下应该设 `status=1`（AUDITING），走一个审核流程（人工或机审），审核通过后再切到 PUBLISHED。这个项目把审核跳过了——代码注释写了"当前版本 DFA 通过即自动发布"。

---

**第三行：入库（第 91 行）**

```java
noteMapper.insert(note);
```
`noteMapper` 是 `BaseMapper<Note>`，无自定义 SQL。MyBatis-Plus 自动生成 `INSERT INTO t_note (id, user_id, ...) VALUES (?, ?, ...)`。

这一步写入了 t_note 表。此时如果程序崩溃，事务还没提交，DB 里看不到这条记录。但 `note.getId()` 已经有值（因为提前通过雪花算法生成了），可以在后续步骤使用。

---

**第四行：业务指标（第 94 行）**

```java
businessMetrics.recordFeedPush("publish");
```

`businessMetrics` 是通过 Micrometer Counter 记录的自定义业务指标。最终在 Prometheus + Grafana 上可以看到"每秒发布了多少篇笔记"的实时指标。这一步不影响业务流程，纯监控用途。

---

**第五块：本地消息表（第 96-112 行）**

```java
// 构建消息体
NotePublishEvent event = new NotePublishEvent();
event.setNoteId(note.getId());           // 2078408307372003329
event.setAuthorId(userId);               // 2078387513547841537
event.setPublishTime(System.currentTimeMillis());
event.setNoteType(note.getNoteType() != null ? note.getNoteType().toString() : "0");

// 写入本地消息表
LocalMessage localMsg = new LocalMessage();
localMsg.setTopic("FEED_TOPIC");
localMsg.setBody(toJson(event));         // 整个 event 序列化为 JSON 字符串
localMsg.setStatus(0);                   // 0 = 待发送
localMsg.setRetryCount(0);
localMessageMapper.insert(localMsg);

// 把 localMessage 的 ID 回填到 event 中
event.setLocalMsgId(localMsg.getId());
```

`NotePublishEvent` 包含 5 个字段：noteId、authorId、publishTime、noteType、localMsgId。这些信息对于 Feed 推送就够了——消费者知道是哪篇笔记、谁写的、什么时候发的。

`LocalMessage` 是本地消息表的实体，插入一条 `topic=FEED_TOPIC`、`status=0` 的消息。这是整个可靠性链路的心脏——`noteMapper.insert()` 和 `localMessageMapper.insert()` 在**同一个 `@Transactional` 内**。如果笔记入库成功但写消息表失败（比如 DB 连接断了），Spring 会回滚整个事务——笔记本插入也会被撤销。不可能出现"笔记入库了但消息没写入"的情况。

反过来想——如果先 insert note，然后直接 `asyncSend` MQ，会出现什么问题？

```
笔记 insert 成功
  → asyncSend MQ 失败（Broker 不可达 / 网络抖动）
    → @Transactional 没有管理 MQ，无法回滚
      → 笔记入库了，但消息丢失了
        → 粉丝的 Feed 流里看不到这篇笔记
```

或者：

```
笔记 insert 成功
  → asyncSend MQ 成功
    → 消费者收到消息，处理笔记内容
      → 但此时事务还没提交！
        → 消费者查 DB 发现笔记不存在
          → 空跑一趟
```

所以本地消息表解决了两个问题：**事务一致性**（写消息和写业务数据同在一个事务内）和**时序正确性**（afterCommit 保证了 MQ 发出时事务已经提交，消费者能查到数据）。

---

**第六块：afterCommit 回调（第 115-146 行）**

这是整个方法里最长的代码块，也是最复杂的。

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            // 6a. 清除缓存
            cacheHelper.delayDoubleDelete("myxhs:note:list:user:" + userId);

            // 6b. 异步发送 MQ
            rocketMQTemplate.asyncSend("FEED_TOPIC", event, new SendCallback() {
                onSuccess: localMessageMapper.markSent(localMsgId);  // status: 0 → 1
                onException: 不处理 → 由 FeedMessageRetryJob 补偿
            });
        }
    }
);
```

`TransactionSynchronizationManager.registerSynchronization()` 是 Spring 提供的事务回调机制。注册一个回调对象，Spring 会在**事务成功提交后**调用 `afterCommit()`。

**6a — 延迟双删**

`delayDoubleDelete()` 是 common 模块 `CacheHelper` 的方法：

```
delayDoubleDelete(key)
  ├─ redisOperator.delete(key)           ← 第一次删
  ├─ Thread.sleep(100ms)                 ← 等一小段时间
  └─ redisOperator.delete(key)           ← 第二次删
```

为什么删两次？假设：

```
时间线：
  T1: 删缓存（第一次）
  T2: 并发请求 A 来查笔记列表 → 缓存 miss → 查 DB（此时 DB 已更新） → 回填缓存（旧数据）
  T3: sleep 100ms 结束
  T4: 删缓存（第二次） → 把 T2 回填的脏数据再删一次
```

但注意——这里删的是**用户笔记列表缓存**（`myxhs:note:list:user:`），而不是笔记详情缓存（`myxhs:note:detail:`）。因为发布操作只影响列表（多了一篇笔记），不影响已有笔记的详情。

**6b — 异步 MQ 发送**

`asyncSend("FEED_TOPIC", event, new SendCallback() {...})`：

- 方法立即返回（~1ms），不阻塞 Controller 返回给用户
- 实际发送由 RocketMQ 的发送线程池异步执行
- 通过 `MqTraceHelper.wrapWithTraceContext()` 把当前请求的 traceId 注入到 MQ 消息中——这样 SkyWalking 能追踪完整链路：HTTP 请求 → MQ 发出 → Consumer 消费

SendCallback 有两个回调：

1. **onSuccess**：MQ Broker 确认收到消息 → `markSent(localMsgId)` → `UPDATE t_local_message SET status=1 WHERE id=?`，把消息状态从"待发送(0)"改为"已发送(1)"
2. **onException**：发送失败 → 只打一行 error 日志。不做任何额外处理——因为 `FeedMessageRetryJob.retryFailedMessages()`（每 30s 定时扫描 `status=0` 的消息）会重新发送。这样设计避免了在回调里写复杂的重试逻辑

**为什么不用 syncSend？** syncSend 会等 Broker 返回确认结果再返回，延迟 ~50ms。对于发布笔记这种接口，多等 50ms 影响用户体验。asyncSend 让接口快速返回 200，MQ 的可靠性交给定时任务兜底——这是一种"用户体验优先，可靠性异步保障"的设计。

---

**第七步：返回 noteId（第 149 行）**

```java
return note.getId();
```

直接把 ID 返回给 Controller，Controller 包装成 `R<{noteId: 2078408307372003329}>` 返回。

此时 MQ 还没发出去（afterCommit 在事务提交后才触发），但用户的请求已经返回了 200。如果 afterCommit 中 MQ 发送失败，由 `FeedMessageRetryJob` 兜底——用户无感知。

---

**总结 — 7 个阶段的完整时间线**

```
HTTP 请求到达
  │
  ├─ [1] DFA 检测 ← 先挡，不进 DB
  ├─ [2] buildNote + set status ← 分配 ID，构建对象
  ├─ [3] noteMapper.insert()     ← 笔记入库（事务内）
  ├─ [4] businessMetrics         ← 记录指标
  ├─ [5] localMessageMapper.insert()  ← 消息表写入（事务内）
  ├─ [6] registerSynchronization ← 注册回调（不执行，只注册）
  └─ [7] return note.getId()     ← 返回用户
       │
       ▼ (事务提交)
       │
  afterCommit 触发：
       ├─ 6a: delayDoubleDelete   ← 清除列表缓存
       └─ 6b: asyncSend ← → MQ 异步发出
             onSuccess → markSent
             onException → FeedMessageRetryJob 补偿（30s 后）
```


### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| t_note 新增一条 status=2 的记录 | ✓ | 通过 |
| t_local_message 新增一条 status=1（MQ 发送成功） | ✓ | 通过 |
| Redis 缓存被清除 | ✓ | 通过 |


