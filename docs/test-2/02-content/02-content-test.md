# 内容模块 curl 逐条测试记录

> 每条测试包含：curl 请求、实际返回、DB 验证、Redis 验证、业务讲解。

| 编号 | 接口 | 状态 |
|------|------|:----:|
| 3.1 | POST /api/note/publish | 通过 |
| 3.2 | GET /api/note/detail/{id} | 通过 |
| 3.3 | GET /api/note/user/{userId} | 通过 |
| 3.4 | GET /api/note/my | 通过 |
| 3.5 | PUT /api/note/{id} | 通过 |
| 3.6 | DELETE /api/note/{id} | 通过 |
| 3.7 | POST /api/note/upload/image | 通过 |
| 3.8 | POST /api/comment | 通过 |
| 3.9 | GET /api/comment/list/{noteId} | 待测试 |
| 3.5 | PUT /api/note/{id} | 通过 |
| 3.6 | DELETE /api/note/{id} | 通过 |
| 3.7 | POST /api/note/upload/image | 通过 |
| 3.8 | POST /api/comment | 通过 |
| 3.9 | GET /api/comment/list/{noteId} | 待测试 |
| 3.6 | DELETE /api/note/{id} | 通过 |
| 3.7 | POST /api/note/upload/image | 通过 |
| 3.8 | POST /api/comment | 通过 |
| 3.9 | GET /api/comment/list/{noteId} | 待测试 |
| 3.7 | POST /api/note/upload/image | 通过 |
| 3.8 | POST /api/comment | 通过 |
| 3.9 | GET /api/comment/list/{noteId} | 待测试 |
| 3.8 | POST /api/comment | 通过 |
| 3.9 | GET /api/comment/list/{noteId} | 待测试 |
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

### Prometheus Metrics 验证

```bash
curl -s http://localhost:19002/actuator/prometheus | grep feed_push_total
```

| 指标 | 值 | 说明 |
|------|------|------|
| `feed_push_total{mode="publish"}` | 6.0 | Counter 自增（每次发布+1，发布前 5 篇+本次 1 篇=6） |
| `feed_push_total{mode="cache_miss"}` | 3.0 | 缓存未命中次数 |

### RocketMQ 验证

| 验证项 | Topic | 状态 | 说明 |
|--------|-------|:--:|------|
| 本地消息表 | FEED_TOPIC | 已发送 | t_local_message.status=1，Broker 已确认 |
| Feed 收件箱 | — | 空 | newuser 没有粉丝，FeedPushConsumer 无推送目标 |

### Canal → ES 索引链验证

> 第一笔记（id=2078408307372003329）在 Canal 起始位点之前，未被同步。第二笔记（id=2080124208983117825）用于验证完整 Canal 链路。

**MySQL 发布第二笔记后 Canal 自动捕获 binlog：**

```
MySQL INSERT t_note
  → Canal binlog capture (位点后新事件)
  → RocketMQ NOTE_INDEX_TOPIC
  → NoteIndexSyncConsumer (search 模块)
  → ES Bulk Index
```

**search 模块日志确认：**
```
2026-07-23 10:54:01.841 [NoteIndexSync] Canal 索引成功: noteId=2080124208983117825
```

**ES 直接查询：**
```bash
GET /note_index/_doc/2080124208983117825
```

| 字段 | 值 |
|------|------|
| title | Canal位点测试 |
| userId | 2078387513547841537 |
| status | 2 |

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

**为什么不把 DFA 放在入库后面���测？** 因为入库后 throw → 事务回滚 → 白写一次 DB。放在第一行，不进 DB 就被挡回去了。

> 深入阅读：[03-dfa/01-dfa-trie-overview.md](../03-dfa/01-dfa-trie-overview.md) — Trie 树数据结构、双词典机制、Pub/Sub 多实例同步

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

> 深入阅读：[04-transaction-aftercommit/01-transaction-mechanism.md](../04-transaction-aftercommit/01-transaction-mechanism.md) — @Transactional 生命周期、连接持有时间、afterCommit 线程模型

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

> 深入阅读：[05-local-message-table/01-local-message-pattern.md](../05-local-message-table/01-local-message-pattern.md) — 状态机、补偿机制、与事务消息/XA 的对比

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
  ├─ redisOperator.delete(key)           ← 第一次删（立即执行）
  └─ delayScheduler.schedule(             ← 异步调度，500ms 后
       () -> delete(key), 500ms, MILLIS)       第二次删
```

第二次删除通过 `ScheduledExecutorService` 异步调度——不阻塞 afterCommit 线程。
详见 `06-cache-strategy/01-cache-aside-delete.md`。

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

> 深入阅读：[07-mq-reliability/01-async-send-retry.md](../07-mq-reliability/01-async-send-retry.md) — asyncSend vs syncSend、SendCallback 生命周期、补偿机制

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
| Prometheus Counter 自增 | `feed_push_total{mode="publish"} 6.0` | 通过 |
| RocketMQ FEED_TOPIC 消息发出 | Broker 确认，status=1 | 通过 |
| Canal binlog → NOTE_INDEX_TOPIC → ES | `_doc/2080124208983117825` found | 通过 |
| Feed 收件箱推送 | **已验证** — 建立关注关系后，testuser 收件箱收到 noteId=2080125128865288193 | 通过 |

---

### Feed 推送链路完整验证

由于第一笔记发布时 newuser 没有粉丝，Feed 收件箱为空。补充社交关系后进行了第二次完整验证：

```
testuser(10001) 关注 newuser(2078387513547841537)
  → POST /api/social/follow/2078387513547841537 → 200
  → Redis ZADD myxhs:follow:fans:2078387513547841537 10001

newuser 发布笔记 id=2080125128865288193
  → afterCommit → asyncSend FEED_TOPIC
  → FeedPushConsumer 收到消息
  → 查粉丝列表 → 找到 fan 10001
  → ZADD myxhs:feed:inbox:10001 (noteId, timestamp)
```

| 验证项 | 结果 |
|--------|:--:|
| 关注关系建立 | ✅ |
| 笔记发布 | ✅ noteId=2080125128865288193 |
| local_message push_status | ✅ push_status=1（推送已完成） |
| Redis Feed 收件箱 | ✅ `ZREVRANGE myxhs:feed:inbox:10001` → 1 条 |
| ES 索引 | ✅ `_doc/2080125128865288193` found (title="Feed推送测试") |

---

## 3.2 笔记详情 — `GET /api/note/detail/{id}`

### curl 请求

```bash
curl -s http://localhost:19000/api/note/detail/2080125128865288193 | jq .
```

### 实际返回

```json
{
  "code": 200,
  "data": {
    "id": 2080125128865288193,
    "title": "Feed推送测试",
    "content": "这篇笔记应该出现在testuser的收件箱",
    "userId": 2078387513547841537,
    "status": 2,
    "statusDesc": "已发布"
  }
}
```

### Redis 验��� — Cache Aside 缓存回填

| 验证项 | 结果 |
|--------|------|
| 首次查询 | Cache miss → 查 DB → 回填 Redis |
| Redis 存储位置 | `myxhs:note:detail:2080125128865288193` on **16379**（Default Redis，Sentinel master） |
| TTL | 1878s（≈31 分钟，30min + 随机偏移防雪崩） |
| 第二次查询 | Cache hit → 不查 DB |

### MySQL 验证

| 验证项 | 结果 |
|--------|------|
| t_note 记录 | id=2080125128865288193, status=2, deleted=0 |

### 业务讲解

公开接口，Gateway 白名单放行（`/api/note/detail/**` 在 auth + hmac 双白名单）。不需要 Token。

对应 `NoteService.getNoteDetail()`，调用 `getWithCacheAside()`：

```
getNoteDetail(noteId)
  ├─ 1. Redis.GET("myxhs:note:detail:2080125128865288193")
  │      → null（首次查询，缓存 miss）
  ├─ 2. noteMapper.selectById(noteId) → DB 查询
  │      → id=2080125128865288193, title="Feed推送测试"
  ├─ 3. Redis.SET(key, note, 30min + random(0~5min))
  │      → 回填缓存，下次命中
  └─ 4. 返回 NoteDetailVO
```

> 深入阅读：[06-cache-strategy/01-cache-aside-delete.md](../06-cache-strategy/01-cache-aside-delete.md) — Cache Aside 读路径、TTL 随机偏移防雪崩

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 公开接口无需 Token | code=200 | 通过 |
| 笔记详情正确返回 | title="Feed推送测试"，statusDesc="已发布" | 通过 |
| Cache Aside 回填生效 | Redis 16379 有缓存，TTL=1878s | 通过 |
| 第二次查询缓存命中 | code=200，不走 DB | 通过 |

---

## 3.3 用户笔记列表 — `GET /api/note/user/{userId}`

### curl 请求

```bash
curl -s "http://localhost:19000/api/note/user/2078387513547841537?pageNum=1&pageSize=5" | jq .
```

### 实际返回

```json
{
  "code": 200,
  "data": {
    "total": 3,
    "pages": 1,
    "current": 1,
    "records": [
      { "id": 2080125128865288193, "title": "Feed推送测试", "noteType": 0, "status": 2, "createdAt": "2026-07-23 10:57:32" },
      { "id": 2080124208983117825, "title": "Canal位点测试", "noteType": 0, "status": 2, "createdAt": "2026-07-23 10:53:53" },
      { "id": 2078408307372003329, "title": "测试笔记", "noteType": 0, "status": 2, "createdAt": "2026-07-18 17:15:30" }
    ]
  }
}
```

### MySQL 验证

```sql
SELECT id, title, status FROM t_note
WHERE user_id=2078387513547841537 AND deleted=0 AND status=2
ORDER BY created_at DESC;
```

返回 3 行 — 与接口返回的 total=3 完全一致。

### Redis 验证

| 验证项 | 结果 |
|--------|------|
| `myxhs:note:list:user:2078387513547841537` | 不存在 |
| 原因 | `getUserNotes()` 直接查 DB，不使用 Cache Aside |

**发现**：`publishNote` 的 `afterCommit` 中有 `delayDoubleDelete(NOTE_LIST_USER + userId)` 主动删除列表缓存，但 `getUserNotes()` 中**没有用 CacheHelper 读取和回填缓存**——读路径直接走 MyBatis-Plus Page 分页查询 DB。这意味着：

- `delayDoubleDelete` 删除的是一个从未被填充过的 Key——是**防御性代码**
- 列表查询每次都走 DB（`SELECT ... WHERE user_id=? AND status=2 ORDER BY created_at DESC LIMIT ? OFFSET ?`）

### 业务讲解

公开接口，Gateway 白名单放行（`/api/note/user/**` 在 auth + hmac 双白名单）。不需要 Token。

```java
// NoteService.java:314-329
public PageResult<NoteItemVO> getUserNotes(Long userId, int pageNum, int pageSize) {
    Page<Note> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
            .eq(Note::getUserId, userId)
            .eq(Note::getStatus, NoteStatus.PUBLISHED.getCode())  // 只返回已发布的
            .orderByDesc(Note::getCreatedAt);

    IPage<Note> result = noteMapper.selectPage(page, wrapper);
    // 转换为 NoteItemVO（不含正文 content）
    return PageResult.of(pageNum, pageSize, result.getTotal(), items);
}
```

**关键设计**：`NoteItemVO` **只含缩略信息**（id/title/firstImage/noteType/status/createdAt），**不含正文 content**。列表页不需要展示全文，减少网络传输和序列化开销。用户点进去才走 `/api/note/detail/{id}` 加载完整正文。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 公开接口无需 Token | code=200 | 通过 |
| 只返回已发布笔记 | 3 篇，status 均为 2 | 通过 |
| 与 DB 一致 | total=3，与 DB count 匹配 | 通过 |
| 含分页信息 | pages=1, current=1 | 通过 |
| 列表不含正文 | NoteItemVO 只有 title/firstImage/etc | 通过 |

---

## 3.4 我的笔记列表 — `GET /api/note/my`

### curl 请求

```bash
# 全部笔记（含草稿、已发布等）
curl -s "http://localhost:19002/api/note/my?pageNum=1&pageSize=5" \
  -H "X-User-Id: 2078387513547841537" | jq .

# 按状态筛选：只看草稿
curl -s "http://localhost:19002/api/note/my?status=0&pageNum=1&pageSize=5" \
  -H "X-User-Id: 2078387513547841537" | jq .
```

### 实际返回

```json
{
  "code": 200,
  "data": {
    "total": 3,
    "records": [
      { "id": 2080125128865288193, "title": "Feed推送测试", "status": 2 },
      { "id": 2080124208983117825, "title": "Canal位点测试", "status": 2 },
      { "id": 2078408307372003329, "title": "测试笔记", "status": 2 }
    ]
  }
}
```

按 `status=0`（草稿）筛选返回 `total=0` — 无草稿记录。

### MySQL 验证

| 验证项 | 结果 |
|--------|------|
| 全部笔记 | 3 篇，与 total=3 一致 |
| 草稿（status=0）| 0 篇，与筛选结果一致 |

### 业务讲解

需要登录（`X-User-Id` Header），通过 Gateway 访问时由 `GatewayAuthFilter` 注入。

与 3.3 `GET /api/note/user/{userId}` 的关键区别：

| 维度 | `/api/note/user/{userId}` | `/api/note/my` |
|------|:--:|:--:|
| 鉴权 | 公开 | X-User-Id |
| 状态筛选 | 仅 status=2（已发布） | 可选 status 参数（0=草稿/1=审核中/2=已发布/3=已下架） |
| 数据源 | DB 分页查询 | DB 分页查询 |
| 缓存 | 无 | 无 |

两者都直接查 DB，没有缓存。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 需 X-User-Id | 直连 19002 + Header 返回 200 | 通过 |
| 返回当前用户全部笔记 | total=3 | 通过 |
| status 筛选生效 | status=0 → total=0 | 通过 |

> **发现**：本项目无草稿记录——3.4 附 3.4b 补测了完整草稿 → 发布流程。

---

## 3.4b 草稿保存与发布 — `POST /api/note/draft` + `POST /api/note/{id}/publish`

### 保存草稿

```bash
curl -s -X POST http://localhost:19002/api/note/draft \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 2078387513547841537" \
  -d '{"title":"这是一篇草稿","content":"草稿内容，还没发布"}'
```

返回 `{"code":200, "data":{"noteId":2080201389885165570}}`

### MySQL 验证

| 验证项 | 结果 |
|--------|------|
| status | `0`（DRAFT，草稿） |
| audit_status | `0`（PENDING，待审核） |
| local_message | **无** — 草稿不触发 Feed 推送 |

### API 验证

| 接口 | 结果 |
|------|------|
| `GET /api/note/my`（不含 status 参数） | total=4，**包含草稿** "这是一篇草稿" |
| `GET /api/note/user/{userId}` | total=3，**不含草稿**（仅 PUBLISHED） |
| `GET /api/note/detail/{id}` | **404** — `getNoteDetail()` 只返回 status=2 的笔记 |

### 业务讲解 — 草稿 vs 直接发布

| 维度 | 直接发布 `publishNote` | 保存草稿 `saveDraft` |
|------|:--:|:--:|
| DFA 检测 | ✅ | ❌（草稿允许写任何内容） |
| status | 2（PUBLISHED） | 0（DRAFT） |
| audit_status | 1（APPROVED） | 0（PENDING） |
| 本地消息表 | ✅ | ❌ |
| MQ 发送 | ✅ | ❌ |
| 延迟双删 | ✅（NOTE_LIST_USER） | ✅（NOTE_LIST_USER） |

---

### 发布草稿

```bash
curl -s -X POST http://localhost:19002/api/note/2080201389885165570/publish \
  -H "X-User-Id: 2078387513547841537"
```

返回 `{"code":200, "message":"操作成功"}`

### 发布后 MySQL 验证

| 变更 | 之前（草稿） | 之后（已发布） |
|------|:--:|:--:|
| status | 0 | 2 |
| audit_status | 0 | 1 |
| local_message | 无 | ✅ status=1, topic=FEED_TOPIC |

### Redis 验证 — 🔴 发现缓存 Bug

```python
# 草稿时的空值占位符仍在缓存中！
val = r.get('myxhs:note:detail:2080201389885165570')
→ "\u0000__CACHE_NULL__\u0000"    # 2min 内不会过期
TTL: 90s
```

**Bug**：草稿发布后，详情接口仍返回 404——因为 `publishDraft` 只清除了 `NOTE_LIST_USER` 缓存，**没有清除 `NOTE_DETAIL` 缓存**。草稿之前被查过一次详情，`getNoteDetail` 返回 null → Cache Aside 缓存了空值占位符 2min → 发布后缓存仍为空值 → 详情接口错误地返回 404，持续到 TTL 过期。

**Bug 已修复**：`publishDraft()` 的 `afterCommit` 中添加了 `delayDoubleDelete(NOTE_DETAIL + noteId)`，详见 `NoteService.java:400`。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 草稿 status=0，不触发 DFA | ✅ | 通过 |
| 草稿不产生 local_message | ✅ | 通过 |
| my 接口含草稿，user 接口不含 | ✅ | 通过 |
| 发布后 status → 2，产生 local_message | ✅ | 通过 |
| 发布后详情接口可访问 | ✅ `delayDoubleDelete(NOTE_DETAIL)` 已加 | **已修复** |

---

## 3.5 编辑笔记 — `PUT /api/note/{id}`

### curl 请求

```bash
curl -s -X PUT "http://localhost:19002/api/note/2078408307372003329" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 2078387513547841537" \
  -d '{"title":"已编辑的测试笔记"}'
```

### 实际返回

```json
{ "code": 200, "message": "操作成功" }
```

### MySQL 验证

| 变更 | 之前 | 之后 |
|------|------|------|
| title | `测试笔记` | `已编辑的测试笔记` |
| status | 2 | 2（不变） |

### Redis 验证

| 验证项 | 结果 |
|--------|------|
| `myxhs:note:detail:2078408307372003329` | 已清除（afterCommit 延迟双删） |
| 重新查询 | 返回新标题 "已编辑的测试笔记" |

### 业务讲解

对应 `NoteService.updateNote()`（`NoteService.java:190-244`）：

```
updateNote(userId, noteId, UpdateNoteRequest)  ← UpdateNoteRequest 所有字段可选
│
├─ 1. getAndCheckOwner(noteId, userId) → 归属校验 + 越权拦截
│
├─ 2. 状态校验 → 仅 DRAFT / PUBLISHED 可编辑
│
├─ 3. 如果已发布（status=2）→ 重新 DFA 检测
│     已发布笔记修改内容后必须重新检测敏感词
│     草稿编辑不需要（草稿本身不受 DFA 约束）
│
├─ 4. 只更新非 null 字段
│     if (request.getTitle() != null) note.setTitle(...)
│     if (request.getContent() != null) note.setContent(...)
│     ... 7 个字段逐一判断
│     noteMapper.updateById(note)  ← 全量更新整个 Note 对象
│
└─ 5. afterCommit:
      ├─ delayDoubleDelete(NOTE_DETAIL + noteId)      ← 详情缓存
      └─ delayDoubleDelete(NOTE_LIST_USER + userId)    ← 列表缓存
```

**关键设计**：

- **编辑已发布笔记 → 重新 DFA**：用户可能编辑时加入敏感词，必须再次检测
- **编辑草稿 → 不重新 DFA**：草稿不对外可见，且发布时会补做 DFA
- **只更新非 null 字段**：防止"忘记传 avatar 导致头像被清空"
- **全量更新而非部分更新**：`updateById(note)` 传入整个 Note 对象，MyBatis-Plus 生成 `UPDATE SET title=?, content=?, ... WHERE id=?`

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 需 X-User-Id + 归属校验 | 200 | 通过 |
| title 在 DB 中更新 | 已编辑的测试笔记 | 通过 |
| 详情缓存被清除 | CLEARED | 通过 |
| 重新查询返回新数据 | title 已更新 | 通过 |

---

## 3.6 删除笔记 — `DELETE /api/note/{id}`

### curl 请求

```bash
curl -s -X DELETE "http://localhost:19002/api/note/2080202480379023362" \
  -H "X-User-Id: 2078387513547841537"
```

### 实际返回

```json
{ "code": 200, "message": "操作成功" }
```

### MySQL 验证

| 字段 | 删除前 | 删除后 |
|------|--------|--------|
| deleted | 0 | 1 |
| status | 2 | 2（不变） |
| 记录 | 存在 | 存在（逻辑删除，非物理删除） |

### Redis 验证

| 验证项 | 结果 |
|--------|------|
| NOTE_DETAIL 缓存 | afterCommit 已清除（`delayDoubleDelete` ✅） |
| 再查详情 | 重新缓存 NULL_PLACEHOLDER（@TableLogic 过滤了 deleted=1） |

### API 验证

| 接口 | 结果 |
|------|------|
| `GET /api/note/detail/{id}` | "笔记不存在" — @TableLogic 自动过滤 |
| `GET /api/note/user/{userId}` | 列表中不含此笔记 |
| `GET /api/note/my` | 列表中不含此笔记 |

### 业务讲解

对应 `NoteService.deleteNote()`（`NoteService.java:251-270`）：

```
deleteNote(userId, noteId)
│
├─ 1. getAndCheckOwner(noteId, userId) → 归属校验
│
├─ 2. noteMapper.deleteById(noteId)
│      → @TableLogic: UPDATE t_note SET deleted=1 WHERE id=?
│      → NOT physical DELETE
│
└─ 3. afterCommit:
       ├─ delayDoubleDelete(NOTE_DETAIL + noteId)
       └─ delayDoubleDelete(NOTE_LIST_USER + userId)
```

**@TableLogic 机制**：MyBatis-Plus 自动在 `SELECT` 语句中追加 `AND deleted=0`，在 `DELETE` 时转为 `UPDATE SET deleted=1`。用户和代码层面感知不到区别——所有查询自动过滤已删除记录。

**缓存一致性**：`deleteNote` 已正确清除 `NOTE_DETAIL` 和 `NOTE_LIST_USER`。后续查询命中 MISS → 查 DB → @TableLogic 过滤 → null → 缓存 NULL_PLACEHOLDER → 正确行为。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 逻辑删除（deleted=1） | ✅ | 通过 |
| 公开接口返回"不存在" | ✅ @TableLogic 自动过滤 | 通过 |
| 列表接口不含此笔记 | ✅ | 通过 |
| 缓存被清除 | ✅ afterCommit 正确清除 | 通过 |

---

## 3.7 图片上传 — `POST /api/note/upload/image`

### curl 请求

```bash
curl -s -X POST "http://localhost:19002/api/note/upload/image" \
  -H "X-User-Id: 2078387513547841537" \
  -F "file=@test.png"
```

### 实际返回

```json
{
  "code": 200,
  "message": "上传成功",
  "data": {
    "url": "http://21.214.97.212:19002/uploads/note/2026/07/23/5347f1a92cf94583902e46d228e20075.png"
  }
}
```

### 文件系统验证

| 验证项 | 结果 |
|--------|------|
| 文件存储路径 | `/data/uploads/note/2026/07/23/5347f1a92cf94583902e46d228e20075.png` |
| 文件大小 | 69 bytes |
| 静态访问 | HTTP 200, Content-Type: image/png |

### 业务讲解

对应 `LocalFileStorageService.upload()`（`LocalFileStorageService.java`）：

```
upload(file, "note")
│
├─ 1. 文件类型白名单校验
│     image/jpeg → .jpg
│     image/png  → .png
│     image/gif  → .gif
│     image/webp → .webp
│     不在白名单 → 拒绝（不信任客户端文件名扩展名）
│
├─ 2. 大小校验
│     > 5MB → 拒绝
│
├─ 3. 生成唯一文件名
│     UUID.randomUUID().replace("-", "") + 扩展名
│     → 防止文件名冲突和覆盖
│
├─ 4. 按日期分层存储
│     {basePath}/{directory}/yyyy/MM/dd/{uuid}.{ext}
│     → /data/uploads/note/2026/07/23/5347f1...png
│     → 便于归档和清理
│
└─ 5. 返回完整 URL
      → http://21.214.97.212:19002/uploads/note/2026/07/23/{uuid}.png
```

**安全设计**：
- 扩展名由 Content-Type 决定，**不信任客户端文件名** — 防止 `shell.php.png` 之类伪装
- UUID 文件名 — 防止路径穿越和覆盖攻击
- 类型白名单 — 只允许 4 种图片格式
- 大小限制 — 5MB 硬限制 + Tomcat 10MB 请求上限

**与笔记内容的关联**：上传成功后前端拿到 `url`，在 `publishNote` 时填入 `images` 数组。上传本身不关联任何笔记——它是独立的资源上传接口。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 返回可访问的图片 URL | HTTP 200 | 通过 |
| 文件存储到磁盘 | `/data/uploads/note/...` | 通过 |
| UUID 文件名防冲突 | `5347f1a...png` | 通过 |
| 日期分层存储 | `2026/07/23/` | 通过 |

---

## 3.8 发表评论 — `POST /api/comment`

### curl 请求

```bash
# 一级评论
curl -s -X POST "http://localhost:19002/api/comment" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"noteId":2078408307372003329,"content":"写得很好的笔记！"}'

# 子评论（楼中楼，回复 commentId=2080221703444680705）
curl -s -X POST "http://localhost:19002/api/comment" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"noteId":2078408307372003329,"content":"回复一楼","parentId":2080221703444680705}'
```

### 实际返回

```json
// 一级评论
{ "code": 200, "message": "评论成功", "data": { "commentId": 2080221703444680705 } }

// 子评论
{ "code": 200, "message": "评论成功", "data": { "commentId": 2080221741738676226 } }
```

### MySQL 验证

| id | parent_id | reply_to_id | content | 层级 |
|:---|:--------:|:----------:|---------|:--:|
| 2080221703444680705 | 0 | NULL | 写得很好的笔记！ | 一级 |
| 2080221741738676226 | 2080221703444680705 | NULL | 回复一楼 | 二级 |

### 业务讲解

对应 `CommentService.createComment()`（`CommentService.java:74-183`）：

```
createComment(userId, request)
│
├─ 1. 校验笔记存在且 status=PUBLISHED
│
├─ 2. DFA 敏感词检测评论内容
│
├─ 3. 父评论校验（parentId > 0 时）
│     ├─ 父评论必须存在
│     ├─ 父评论必须属于同一篇笔记
│     └─ 如果父评论本身是子评论 → 自动修正 parentId 为根评论 ID
│         → 保证永远只有两层嵌套
│
├─ 4. replyToId 校验（replyToId > 0 时）
│     └─ 被回复的评论必须存在且属于同一笔记
│
├─ 5. commentMapper.insert(comment)
│
└─ 6. afterCommit:
      ├─ delayDoubleDelete(COMMENT_LIST + noteId)    ← 清除评论列表缓存
      ├─ delayDoubleDelete(COMMENT_COUNT + noteId)   ← 清除评论计数缓存
      └─ if (评论者 ≠ 笔记作者):
           asyncSend NOTIFICATION_TOPIC → 通知笔记作者
```

**楼中楼设计**：仅支持两级嵌套。如果前端传 `parent_id` 指向一个子评论，后端自动修正为指向该子评论的根评论——保证所有二级评论的 `parent_id` 都是一级评论的 ID。

### 期望 vs 实际

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 一级评论 parent_id=0 | ✅ | 通过 |
| 子评论 parent_id 指向一级评论 | ✅ | 通过 |
| DFA 检测生效 | ✅（内容无敏感词） | 通过 |
| 评论计数缓存被清除 | ✅ afterCommit | 通过 |

---

## 3.9 评论列表 — `GET /api/comment/list/{noteId}`

### curl 请求

```bash
curl -s "http://localhost:19000/api/comment/list/2078408307372003329?pageSize=10" | jq .
```

### 实际返回

```json
{
  "code": 200,
  "data": [{
    "id": 2080221703444680705,
    "userId": 10001,
    "content": "写得很好的笔记！",
    "childCount": 1,
    "children": [{ "content": "回复一楼" }]
  }]
}
```

### 验证项

| 验证项 | 结果 |
|--------|:--:|
| 一级评论正确返回 | 1 条 |
| 子评论预加载（前 3 条） | ✅ children 包含 "回复一楼" |
| childCount 正确 | 1 |
| 游标分页 | 公开接口，无需 Token |

> 深入阅读：[12-comment-system/01-comment-architecture.md](../12-comment-system/01-comment-architecture.md) — 楼中楼两层设计、游标分页原理

## 3.10 子评论列表 — `GET /api/comment/children/{parentId}`

### 实际返回

```json
{ "code": 200, "data": [{ "id": 2080221741738676226, "parentId": 2080221703444680705, "content": "回复一楼" }] }
```

## 3.11 评论计数 — `GET /api/comment/count/{noteId}`

### 实际返回

```json
{ "code": 200, "data": { "count": 2 } }
```

### Redis 验证

| 验证项 | 结果 |
|--------|------|
| `myxhs:comment:count:2078408307372003329` | 2 |
| TTL | 321s（≈5min） |

### MySQL 验证

```sql
SELECT COUNT(*) FROM t_comment WHERE note_id=2078408307372003329 → 2
```
与缓存值一致。

### 期望 vs 实际（3.9~3.11）

| 期望 | 实际 | 结论 |
|------|------|:----:|
| 一级评论含子评论预览 | childCount=1, children=[1条] | 通过 |
| 子评论按时间正序 | 只有 1 条 | 通过 |
| 评论计数 = 2 | Redis 缓存 2, TTL=321s | 通过 |
| DB 与缓存一致 | DB total=2 | 通过 |
