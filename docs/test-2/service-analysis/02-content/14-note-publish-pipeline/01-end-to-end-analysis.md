# 笔记发布全链路深度剖析

> **代码版本**: 2026-08-04 deep review  
> **涉及源码**: NoteController / NoteService / FeedPushConsumer / LocalMessage / FeedMessageRetryJob  
> **关键修复**: localMsgId 传递（坑18）、延迟双删缓存、异步推送回调标记

---

## 1. 架构总览

```
Gateway(19000) → NoteController(19002)
                      │
                      ▼
              NoteService.publishNote()
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
      DFA检测    INSERT笔记   本地消息表
                    │           │
                    ▼           │
              TransactionSynchronizationManager
                    │
              afterCommit()
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
     延迟双删缓存       MQ asyncSend(FEED_TOPIC)
     (Caffeine+Redis)  ← 实际未使用Caffeine，仅Redis  │        // 真实源码仅用 Redis，无本地缓存
                               ▼
                     FeedPushConsumer(home模块)
                               │
                     ┌─────────┴─────────┐
                     ▼                   ▼
              推送到粉丝收件箱         更新推送进度
              (Redis List)          (Redis Hash)
```

整个链路涉及 **3 个微服务**、**2 个数据库**（MySQL + Redis）、**1 个消息队列**（RocketMQ）。

---

## 2. 入口层：Controller + RateLimit

```java
// NoteController.java:41-49
@PostMapping("/publish")
@RateLimit(windowSeconds = 60, maxRequests = 5, perUser = true, prefix = "note:publish",
        message = "发布过于频繁，请稍后重试")
public R<Map<String, Long>> publishNote(
        @RequestHeader("X-User-Id") Long userId,
        @Valid @RequestBody NotePublishRequest request) {
    Long noteId = noteService.publishNote(userId, request);
    return R.ok("发布成功", Map.of("noteId", noteId));
}
```

### 设计决策分析

| 决策 | 为什么 |
|------|--------|
| `@RateLimit(5/60s)` | 同一用户每分钟最多 5 篇——防止刷帖攻击。对齐 B 站/小红书的发布频率上限 |
| `perUser = true` | 按 userId 而非 IP 限流——生产环境有 NAT 网关，IP 限流会误伤同网络用户 |
| `X-User-Id` Header | 由 Gateway 从 JWT Token 解析后注入，服务层不做 JWT 解码 |
| `@Valid` | Jakarta Bean Validation，字段校验在进入 Service 之前完成 |

### Request DTO 详解

```java
// NotePublishRequest.java
@Data
public class NotePublishRequest {
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最多100字")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Size(max = 20000, message = "内容最多20000字")
    private String content;

    private List<String> images;      // 图片 URL 列表
    private String videoUrl;          // 视频 URL
    private String coverUrl;          // 封面图 URL
    private List<Long> topicIds;      // 话题 ID 列表
    private List<String> tags;        // 标签列表
    private Integer noteType;         // 0=图文 1=视频
}
```

---

## 3. 核心业务层：NoteService.publishNote()

```java
// NoteService.java:81-150
@Transactional(rollbackFor = Exception.class)
public Long publishNote(Long userId, NotePublishRequest request) {
    // 1. DFA 敏感词检测
    checkSensitiveWords(request.getTitle(), request.getContent());
    
    // 2. 构建笔记实体
    Note note = buildNote(userId, request);
    note.setStatus(NoteStatus.PUBLISHED.getCode());
    note.setAuditStatus(AuditStatus.APPROVED.getCode());
    
    // 3. 入库
    noteMapper.insert(note);
    
    // 4. 写入本地消息表
    NotePublishEvent event = new NotePublishEvent();
    event.setNoteId(note.getId());
    event.setAuthorId(userId);
    event.setPublishTime(System.currentTimeMillis());
    
    LocalMessage localMsg = new LocalMessage();
    localMsg.setTopic("FEED_TOPIC");
    localMsg.setBody(toJson(event));
    localMsg.setStatus(0);  // 待发送
    localMsg.setRetryCount(0);
    localMessageMapper.insert(localMsg);
    
    event.setLocalMsgId(localMsg.getId());  // ★关键：传递 localMsgId
    
    // 5. afterCommit: 缓存 + MQ
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheHelper.delayDoubleDelete(...);
                rocketMQTemplate.asyncSend("FEED_TOPIC", message, callback);
            }
        });
    
    return note.getId();
}
```

### 3.1 本地消息表模式

```
┌─────────────────────────────────┐
│  同一个 @Transactional 事务内    │
│                                 │
│  INSERT t_note        ✓         │
│  INSERT t_local_message  ✓      │
│                                 │
│  事务 COMMIT → afterCommit()   │
│      ↓                          │
│  MQ asyncSend(FEED_TOPIC)       │
│      ↓                          │
│  成功 → markSent(localMsgId)    │
│  失败 → FeedMessageRetryJob 重试 │
└─────────────────────────────────┘
```

**为什么要本地消息表？**

MySQL 事务保证了笔记和本地消息的原子性——要么都成功，要么都回滚。MQ 发送失败时：
1. 本地消息表有一条 `status=0` 的记录（待发送）
2. `FeedMessageRetryJob` 定时扫描 `status=0` 的记录，重新发送
3. 最大重试 60 次，指数退避

这比直接 `syncSend` 好在：
- 不阻塞用户请求（用户看到"发布成功"时，笔记已经入库，消息表已有记录）
- MQ 积压不影响用户响应时间
- 最多重试 3 次后标记为死信（status=3），避免无限重试浪费 MQ 资源

### 3.2 TransactionSynchronization.afterCommit

这是 Spring 事务管理器的回调接口。只有 `@Transactional` 的事务真正 COMMIT 到数据库后才执行。

**为什么必须 afterCommit，不能直接在方法内发 MQ？**

| 场景 | 直接发 MQ | afterCommit 发 MQ |
|------|-----------|-------------------|
| 事务正常提交 | ✅ | ✅ |
| 事务回滚（如敏感词检测抛异常） | ❌ MQ 消息已发出，粉丝看到幻影笔记 | ✅ beforeCommit 不会执行 |
| DFA 检测抛异常 | ❌ 同上 | ✅ rollback 触发，afterCommit 不执行 |

### 3.3 localMsgId 传递

```java
// MyBatis-Plus IdType.ASSIGN_ID (雪花算法) 在 insert 前自动赋值
// insert 后 localMsg.getId() 返回已分配的 ID
localMessageMapper.insert(localMsg);
event.setLocalMsgId(localMsg.getId());
```

这个 `localMsgId` 会随着 MQ 消息传给 FeedPushConsumer，用于：

```
FeedPushConsumer 推送笔记到粉丝 A 的收件箱
    → 更新推送进度: HSET feed:push:progress:localMsgId userId 1
    → 补偿检查: HGET feed:push:progress:localMsgId 是否完成
```

如果 `localMsgId` 没有设置（之前已修复的坑18），补偿任务 `FeedMessageRetryJob` 无法判断哪些粉丝已推送、哪些未推送，导致：
- 全部粉丝重新推送（重复消费）
- 或者全部不推送（消息丢失）

---

## 4. 敏感词过滤层：DFA Trie 树

```java
// NoteService.java
private void checkSensitiveWords(String title, String content) {
    SensitiveWordResult result = sensitiveWordFilter.check(title + content);
    if (result.isSensitive()) {
        throw new BizException(ResultCode.CONTENT_HAS_SENSITIVE_WORD,
            "内容包含敏感词: " + result.getFirstWord());
    }
}
```

### DFA 算法详解

DFA（Deterministic Finite Automaton，确定有穷自动机）将敏感词构建为一棵 Trie 树：

```
敏感词库: { "色情", "色情的", "赌博" }
生成 Trie:
    root
    ├── 色 → 情 → [结束] → 的 → [结束]
    ├── 赌 → 博 → [结束]
```

**时间复杂度**: O(n) —— n = 文本长度，与词库大小无关。这是 DFA 相比 List.contains() 遍历的核心优势。

**当前限制**（已知设计决策）：
- 词库规模不大，仅覆盖常见敏感词
- 不包含变体识别（如 "sè qíng" 拼音、Unicode 同形字）
- 生产环境建议对接第三方审核 API（如阿里云内容安全）

---

## 5. 缓存策略：延迟双删

```java
// TransactionSynchronization.afterCommit() 中
cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + userId);
```

### 为什么是延迟双删，而不是单次删除？

用户发布笔记后，自己刷新列表应该立即看到新笔记。标准 Cache-Aside 流程是：

```
1. 写 DB
2. 删缓存
```

但在高并发下，有并发问题：

```
时间线:
T1: 用户 A 发布笔记 → DB: INSERT → 删缓存
T2: 用户 A 立即刷新列表
T3: Nginx → Gateway → Content → Redis(未命中) → DB(查到新笔记) → 写回 Redis
T4: 用户 B 也刷新列表（命中 Redis，包含 A 的新笔记）✓

但如果 T2 和 T3 之间有另一个写操作：

T2: 用户 B 编辑自己的笔记 → DB: UPDATE → 删缓存(A 的)
T3: Redis 回填（但回填的是 A 的读操作在 T2 之前查到的旧数据！）
结果: A 的旧缓存覆盖了 B 的新写入，B 的笔记列表包含脏数据
```

**延迟双删解决方案**：

```
1. 删缓存（立即）
2. 写 DB  ←── 事务提交
3. 等 500ms
4. 再删一次缓存（二次确认）
```

500ms 窗口内，读取操作会将旧数据写回缓存（脏缓存），二次删除会清除它。但是窗口内如果恰好有业务读把旧数据写回，二次删才生效。代价是 500ms 内用户可能看到旧数据。

---

## 6. MQ 推送链路：从生产到消费

### 6.1 生产者：asyncSend + 回调

```java
rocketMQTemplate.asyncSend("FEED_TOPIC", 
    MqTraceHelper.wrapWithTraceContext(
        MessageBuilder.withPayload(event).build()), 
    new SendCallback() {
        @Override
        public void onSuccess(SendResult sendResult) {
            // 发布成功 → 标记本地消息已发送
            localMessageMapper.markSent(localMsgId);
            log.info("Feed推送成功, noteId={}", finalNoteId);
        }
        @Override
        public void onException(Throwable e) {
            // 发布失败 → 不标记，依赖补偿任务重试
            log.error("Feed推送失败(补偿任务重试), noteId={}", finalNoteId, e);
        }
    });
```

**关键设计**: MQ 发送使用 `asyncSend`（异步），因为回调中已有成功/失败处理：
- 成功 → 标记本地消息
- 失败 → 不标记，补偿任务重试

### 6.2 消费者：FeedPushConsumer（home 模块）

```java
// FeedPushConsumer.java (home模块，真实源码简化)
@RocketMQMessageListener(
    topic = "FEED_TOPIC",
    consumerGroup = "feed-push-consumer-group",
    maxReconsumeTimes = 3        // 最多重试3次
)
public class FeedPushConsumer implements RocketMQListener<MessageExt> {
    @Override
    public void onMessage(MessageExt msg) {
        // 1. 解析消息
        NotePublishEvent event = parseEvent(msg);
        Long noteId = event.getNoteId();
        Long authorId = event.getAuthorId();
        
        // 2. 通过 AnalyticsFeignClient 获取粉丝列表
        List<Long> followerIds = getFollowerBatches(authorId);
        
        // 3. Pipeline 批量 ZADD 到每个粉丝的收件箱
        //    Key: feed:timeline:{followerId}
        //    Member: noteId, Score: publishTime
        for (int i = 0; i < followerIds.size(); i += BATCH_SIZE) {
            List<Long> batch = followerIds.subList(i, Math.min(i + BATCH_SIZE, followerIds.size()));
            pushBatchToTimelines(batch, noteId, event.getPublishTime());
        }
    }
}
```

**关键设计点**：
1. **无 selectorExpression** — 消费 FEED_TOPIC 上所有消息，不按 Tag 过滤
2. **maxReconsumeTimes=3** — 失败最多重试 3 次，避免死循环
3. **Pipeline 批量写入** — 粉丝的 Timeline Redis List 用 `ZADD` 原子操作，`Pipeline` 减少 N 次网络往返为 1 次
4. **AnalyticsFeignClient** — 通过 Feign 调用 analytics 服务获取粉丝列表（不是直接查 content 的 DB）

### 6.3 推模式 vs 拉模式的权衡

| 模式 | 优势 | 劣势 | my-xhs 的选择 |
|------|------|------|-------------|
| **推模式**（当前） | 粉丝 Feed 实时更新，查询 O(1) | 写扩散：1 个 V 发笔记，100 万粉丝等待 | 对大 V 限流（MAX_BATCH=5000） |
| **拉模式** | 读扩散小，V 发笔记几乎无开销 | 粉丝查看 Feed 时需要聚合所有关注的 V | 不适合实时场景 |
| **推拉结合** | 小 V 推，大 V 拉 | 实现复杂，需要判定大 V 阈值 | 未来优化方向 |

当前实现用纯推模式，`FeedPushConsumer` 逐级推送到大 V 粉丝的收件箱。大 V 场景下推送延迟可能较高（10s+）。

---

## 7. 补偿机制：FeedMessageRetryJob

当 MQ 发送失败时，`afterCommit` 回调中不标记 `localMsg` 为已发送，`FeedMessageRetryJob` 会定时扫描：

```java
// FeedMessageRetryJob.java (content模块)
@Scheduled(fixedDelay = 30000)  // 每30秒
public void retryFailedMessages() {
    List<LocalMessage> pending = localMessageMapper.selectList(
        new LambdaQueryWrapper<LocalMessage>()
            .eq(LocalMessage::getStatus, 0)  // 待发送
            .lt(LocalMessage::getRetryCount, 60));  // 最多60次
    
    for (LocalMessage msg : pending) {
        // 补偿推送前检查进度
        if (hasAllFollowersReceived(msg)) {
            localMessageMapper.markSent(msg.getId());
            continue;
        }
        // 重新发送
        NotePublishEvent event = parseEvent(msg.getBody());
        rocketMQTemplate.asyncSend(msg.getTopic(), event, callback);
        msg.setRetryCount(msg.getRetryCount() + 1);
    }
}
```

---

## 8. 完整请求时序

```
时间 | 层级            | 操作
-----|-----------------|------
T+0  | Gateway         | JWT 解析 → 注入 X-User-Id
T+1  | @RateLimit      | 检查 5次/分钟 限制
T+2  | @Valid          | Bean Validation 字段校验
T+3  | NoteService     | DFA 敏感词检测 (O(n))
T+4  | @Transactional  | BEGIN TRANSACTION
T+5  | INSERT t_note   | 笔记入库
T+6  | INSERT t_local_message | 本地消息表
T+7  | COMMIT          | 事务提交
T+8  | afterCommit     | 延迟双删缓存 + MQ asyncSend
T+9  | Return 200      | 用户看到"发布成功"
---  | 异步             | ---
T+10 | RocketMQ        | FEED_TOPIC 消息投递
T+20 | FeedPushConsumer| 解析消息 → 获取粉丝列表
T+50 | Timeline推送    | 写入粉丝 Redis List（最大5000条）
T+51 | 推送进度更新     | HSET feed:push:progress
```

**端到端延迟**: ~50ms（正常情况），~5s（大 V 有大量粉丝时）。

---

## 9. 故障场景分析

### 场景 1：MQ 发送失败
| 阶段 | 状态 |
|------|------|
| INSERT t_note | ✅ 已持久化 |
| INSERT t_local_message | ✅ status=0 |
| MQ asyncSend | ❌ 失败 |
| 用户看到的 | "发布成功" |
| 补偿 | FeedMessageRetryJob 30s后重试 |
| 最大容忍 | 30s（补偿间隔） |

### 场景 2：事务回滚
| 触发 | DFA 检测到敏感词 → BizException → @Transactional 回滚 |
|------|------|
| t_note 回滚 | ✅ |
| t_local_message 回滚 | ✅ |
| afterCommit 执行 | ❌ 不会执行 |
| 用户看到的 | "内容包含敏感词" |

### 场景 3：FeedPushConsumer 消费失败
| 阶段 | 状态 |
|------|------|
| MQ 消息 | 已投递 |
| consumer 处理 | ❌ 抛异常 |
| RocketMQ 重试 | 最多重试 maxReconsumeTimes 次 |
| 重试全部失败 | 进入死信队列 (DLQ) |
| 最终兜底 | FeedMessageRetryJob 重新推送 |

---

## 10. 知识点索引

| 知识点 | 涉及模块 | 源码位置 |
|--------|---------|---------|
| 本地消息表模式 | content | `NoteService.java:96-109` |
| `TransactionSynchronization.afterCommit` | content | `NoteService.java:118-147` |
| DFA Trie 树敏感词过滤 | content | `SensitiveWordFilter.java` |
| 延迟双删缓存策略 | content | `CacheHelper.java` |
| MQ asyncSend + 回调 | content | `NoteService.java:126-142` |
| 推模式 Feed 分发 | home | `FeedPushConsumer.java` |
| 补偿重试 | content | `FeedMessageRetryJob.java` |
| 大 V 限流 | home | `FeedPushConsumer.java MAX_BATCH=5000` |

---

## 11. 面试要点

**Q1**: publishNote 为什么用 `@Transactional`，不用 `@Transactional(propagation = REQUIRES_NEW)`？

**A**: 默认的 `REQUIRED` 传播级别可以复用已有事务。如果调用方已经有事务，不会新开事务。这里用 `REQUIRES_NEW` 反而不合理——因为笔记入库和本地消息表 INSERT 必须在同一个事务里。

**Q2**: asyncSend 的回调在哪个线程执行？

**A**: RocketMQ Netty IO Worker 线程。回调中的 `localMessageMapper.markSent(localMsgId)` 操作 MySQL，但不在原事务中。这是有意设计的——即使回调失败，补偿任务会重新扫描 `status=0` 的记录。

**Q3**: 如果用户在 MQ 发送之前又发了一篇笔记，会影响第一篇笔记的粉丝推送吗？

**A**: 不会。每篇笔记有独立的 `localMsgId`，补偿任务按 `localMsgId` 独立追踪推送进度。

**Q4**: 为什么选择了推模式而不是拉模式？

**A**: 推模式在粉丝数较少时效果最优——粉丝打开 App 时 Feed 已经准备好。大 V 场景通过 MAX_BATCH 限流缓解写放大。如果用户规模增长到千万级别，需要迁移到推拉结合模式。

**Q5（陷阱）**: `event.setLocalMsgId(localMsg.getId())` —— 这段代码在 `localMessageMapper.insert(localMsg)` 之后执行。MyBatis-Plus 的 `insert` 会回填主键 ID（`useGeneratedKeys`）。如果没有回填，`localMsg.getId()` 返回 null，MQ 消息体中的 `localMsgId` 就是 null——补偿任务无法追踪推送进度。

---

*下一篇: [TODO 选题]*
