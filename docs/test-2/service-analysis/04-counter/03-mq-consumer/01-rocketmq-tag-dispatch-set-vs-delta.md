# RocketMQ 计数消费：Tag 分发 + Set vs Delta + 跨服务解耦

> **源码**: CounterEventConsumer(192行→222行) + LikeEvent(54行)/FavoriteEvent(52行) + CommentService sendCommentCounterEvent + NoteService SHARE  
> **Topic**: `SOCIAL_TOPIC` | **Group**: `counter-consumer-group` | **Tags**: `LIKE||UNLIKE||FAVORITE||UNFAVORITE||COMMENT||UNCOMMENT||SHARE`  
> **事件流**: analytics/content 服务 → RocketMQ → counter 服务 → Redis 原子更新 + Buffer 刷盘  
> **关键修复**: R1 COMMENT/UNCOMMENT + R4 SHARE 消费路径 | R5 TargetType PRODUCT(4)

---

## 1. 数据流全景

```
┌──────────────────────────┐          ┌─────────────────────────────┐
│  my-xhs-analytics        │          │  my-xhs-counter             │
│                          │          │                             │
│  LikeService             │ syncSend │  CounterEventConsumer       │
│  ─────────────────────►│──SOCAL──►│  ─┬─ handleLikeEvent()     │
│    "LIKE" / "UNLIKE"    │  TOPIC   │   │  → setBasedLikeWithDedup│
│                          │          │   │                        │
│  FavoriteService         │          │   └─ handleFavoriteEvent() │
│  ─────────────────────►│          │     → incrementWithDedup   │
│    "FAVORITE"/"UNFAVORITE"         │     → decrementWithDedup   │
│                          │          │                             │
└──────────────────────────┘          └─────────────────────────────┘

消息体格式 (JSON Map):
  LIKE/UNLIKE:  {"userId":100, "bizType":1, "bizId":20001, "action":"LIKE", "actionTime":1720000000000}
  FAVORITE/UNFAVORITE: {"userId":100, "noteId":20001, "action":"FAVORITE", "actionTime":1720000000000}
```

注意：`CounterEventConsumer` **不反序列化为强类型 DTO**（`LikeEvent`/`FavoriteEvent`），而是用 `ObjectMapper.readValue(message, Map.class)`（行 73）。原因：这两个类在 analytics 模块中，counter 不依赖 analytics 模块的 jar。这保持了模块间的**编译时解耦**——counter 只需要知道 JSON 字段结构，不需要 import analytics 的类型。

---

## 2. Consumer 配置 (`CounterEventConsumer.java:42-47`)

```java
@RocketMQMessageListener(
    topic = "SOCIAL_TOPIC",
    selectorExpression = "LIKE||UNLIKE||FAVORITE||UNFAVORITE",
    consumerGroup = "counter-consumer-group",
    maxReconsumeTimes = 3
)
```

| 配置项 | 值 | 原因 |
|------|------|------|
| `topic` | `SOCIAL_TOPIC` | 社交行为统一 Topic，analytics 服务发布 |
| `selectorExpression` | 4 个 Tag | SQL92 过滤，Broker 端拒绝不相关 Tag（减少网络传输） |
| `consumerGroup` | `counter-consumer-group` | 独立消费组，不与其他模块（如 analytics 自己的消费者）竞争 |
| `maxReconsumeTimes` | 3 | 3 次后投递死信；去重/归零保护返回 false 时直接 ACK，不抛异常 |

---

## 3. 消息处理主流程 (`onMessage()`, 行 62-93)

```java
@Override
public void onMessage(MessageExt msg) {
    MqTraceHelper.restoreTraceContext(msg);  // 1. 恢复全链路 trace
    String msgId = msg.getMsgId();
    try {
        String tag = msg.getTags();
        Map<String, Object> eventMap = 
            objectMapper.readValue(new String(msg.getBody(), UTF_8), Map.class);

        switch (tag) {
            case "LIKE": case "UNLIKE": 
                handleLikeEvent(msgId, eventMap, tag);
                break;
            case "FAVORITE": case "UNFAVORITE":
                handleFavoriteEvent(msgId, eventMap, tag);
                break;
        }
    } catch (Exception e) {
        throw new RuntimeException("计数消息消费失败", e);  // → 触发 MQ 重试
    } finally {
        MqTraceHelper.clearTraceContext();  // 清理 MDC（防止线程池污染）
    }
}
```

### 3.1 trace 恢复机制

`MqTraceHelper.restoreTraceContext(msg)` 从 RocketMQ 消息 Properties 中恢复 `X-Trace-Id`：
1. 提取 Properties 中的 `traceId` → 设置到 MDC
2. 恢复 ThreadLocal trace 上下文（SkyWalking 兼容）
3. `finally` 中 `clearTraceContext()` 防止线程池复用时的 MDC 残留

这与 SkyWalking 的自动传播是独立的两套系统：
- SkyWalking 通过 `sw8` 头自动传播（agent 字节码注入）
- `MqTraceHelper` 是业务级别的 traceId，用于日志关联

### 3.2 异常处理策略

```
消费异常 → throw RuntimeException → RocketMQ 重试 (max3次)
去重拦截 → status=0 → 直接返回 (不抛异常, ACK)       ← "静默消费"
归零保护 → status=-1 → 直接返回 (不抛异常, ACK)      ← "有效业务拒绝"
```

关键区分：**只有真正的异常（反序列化失败、Redis 调用失败）才触发重试**。去重和归零保护是正常的业务逻辑，不需要重试。

---

## 4. `handleLikeEvent()` —— Set-based 分发 (`CounterEventConsumer.java:107-139`)

### 4.1 bizType 映射

```java
// CounterEventConsumer.java:121-129
if (bizType == 1)      targetType = TARGET_TYPE_NOTE;     // 1=笔记
else if (bizType == 2) targetType = TARGET_TYPE_COMMENT;  // 3=评论
else                    log.warn + return;                 // 未知

boolean isLike = "LIKE".equals(tag);
counterService.setBasedLikeWithDedup(msgId, targetType, bizId, userId, isLike);
```

**全部分发逻辑 — 一个 5 分支决策树**：

```
LIKE(bizType=1)  → targetType=NOTE(1)  → SADD → SCARD → 笔记点赞数+1
LIKE(bizType=2)  → targetType=COMMENT(3)→ SADD → SCARD → 评论点赞数+1 ← [修复H4]
UNLIKE(bizType=1)→ targetType=NOTE(1)  → SREM → SCARD → 笔记点赞数-1
UNLIKE(bizType=2)→ targetType=COMMENT(3)→ SREM → SCARD → 评论点赞数-1
bizType=其他      → 日志警告 + 跳过
```

### 4.2 为什么 bizType=2 映射到 targetType=3（COMMENT）而不是 1（NOTE）？

这是修复 H4 的关键。修复前：
- LIKE(bizType=2) 映射到 targetType=1（NOTE），用 `noteId` 作为 targetId
- 结果：评论点赞数被错误累加到笔记的点赞数上，**笔记点赞数虚增**

修复后：
- LIKE(bizType=2) 映射到 targetType=3（COMMENT），用 `bizId`（即 commentId）
- 结果：评论点赞数独立计数，与笔记点赞分离

**修复前的影响范围**：任何对评论的点赞都会被错误统计为笔记点赞。
**修复的完整性**：TargetType 枚举也已添加 COMMENT(3)（`TargetType.java:16`）。

### 4.3 userId 缺失处理 (`CounterEventConsumer.java:116-119`)

```java
if (userId == null) {
    log.warn("点赞事件缺少 userId，无法使用 Set-based 计数");
    return;  // 静默跳过，不触发重试
}
```

Set-based 计数需要 userId 作为 SADD/SREM 的 member。如果 LikeEvent 缺少 userId（可能是旧版本 analytics 的数据），Consumer 直接跳过并 ACK。

**为什么只 log.warn 而不抛异常？** 因为消息本身是有效的——用户确实点赞了——只是数据格式不完整。抛异常会导致 MQ 无限重试，浪费资源。正确的做法是修复 analytics 端的 LikeEvent 构建逻辑，确保包含 userId。

---

## 5. `handleFavoriteEvent()` —— Delta-based (`CounterEventConsumer.java:149-169`)

```java
private void handleFavoriteEvent(String msgId, Map<String, Object> eventMap, String tag) {
    Long noteId = toLong(eventMap.get("noteId"));
    if (noteId == null) return;  // 字段缺失，跳过

    if ("FAVORITE".equals(tag)) {
        counterService.incrementWithDedup(msgId, TARGET_TYPE_NOTE, noteId, COUNT_TYPE_FAVORITE);
    } else {
        counterService.decrementWithDedup(msgId, TARGET_TYPE_NOTE, noteId, COUNT_TYPE_FAVORITE);
    }
}
```

收藏计数使用的是 delta 模型（而非 Set-based），固定映射：
- targetType = 1（NOTE — 收藏只在笔记维度）
- countType = 2（COLLECT）

**为什么收藏用 delta 而不用 Set？**
- 收藏是单纯的计数，不像点赞需要区分"谁收藏了"（收藏列表由 analytics 的 FavoriteService 独立管理）
- 收藏的乱序风险远低于点赞（收藏流量的并发度远低于点赞，MQ 乱序概率低）
- 且 delta 方案更简单（不需要额外的 Redis Set + SCARD），性能更好

**收藏的归零保护场景**：
```
1. 笔记被取消收藏 (noteId=20001, UNFAVORITE)
2. 此时该笔记从未被收藏（Redis count=0）
3. decrementWithDedup → Lua 脚本 → redisCount=0 → status=-1 → 归零保护
4. Consumer 收到返回 false → 静默 ACK，不抛异常
```

这个场景在生产中可能出现：用户取消了收藏，但该笔记本来就没有被收藏记录。可能是因为其他服务（如数据迁移/补偿）发送的冗余 UNFAVORITE。

---

## 5b. `handleCommentEvent()` — 评论计数【修复R1】(`CounterEventConsumer.java:184-207`)

```java
private void handleCommentEvent(String msgId, Map<String, Object> eventMap, String tag) {
    Long noteId = toLong(eventMap.get("noteId"));
    if (noteId == null) {
        log.warn("评论事件缺少必填字段: noteId={}", noteId);
        return;
    }

    if ("COMMENT".equals(tag)) {
        counterService.incrementWithDedup(msgId, TARGET_TYPE_NOTE, noteId, COUNT_TYPE_COMMENT);
    } else {
        counterService.decrementWithDedup(msgId, TARGET_TYPE_NOTE, noteId, COUNT_TYPE_COMMENT);
    }
}
```

评论计数使用 delta 模型（与收藏相同），固定映射：
- targetType = 1（NOTE — 评论数量的维度是笔记）
- countType = 3（COMMENT）

**修复前**：CounterEventConsumer 只订阅 LIKE||UNLIKE||FAVORITE||UNFAVORITE，缺少 COMMENT/UNCOMMENT 处理。CommentService 通过 `delayDoubleDelete(COMMENT_COUNT)` 管理自己的评论缓存，但 counter 服务的 countType=3 从未被写入。home 服务通过 `batchGetCounts` 查询 countType=3 → **评论数始终为 0**。

**修复后**：
1. `CounterEventConsumer` selector 加 `||COMMENT||UNCOMMENT`
2. CommentService 在 `afterCommit` 中通过 `sendCommentCounterEvent()` 发送 `SOCIAL_TOPIC:COMMENT/UNCOMMENT`
3. counter 服务正确更新评论计数 → home 展示正常

**设计决策 — 为什么用 asyncSend 而不是 syncSend？**
- 评论发表的 afterCommit 回调中已执行缓存清理和通知发送
- counter 更新是异步计数，不需要阻塞评论发表事务
- asyncSend 失败时：SendCallback.onException 记日志 → 凌晨对账以 Redis 值（从 `COMMENT_COUNT` 键重建）为准

---

## 5c. `handleShareEvent()` — 分享计数【修复R4】(`CounterEventConsumer.java:215-228`)

```java
private void handleShareEvent(String msgId, Map<String, Object> eventMap) {
    Long noteId = toLong(eventMap.get("noteId"));
    if (noteId == null) {
        log.warn("分享事件缺少必填字段");
        return;
    }
    counterService.incrementWithDedup(msgId, TARGET_TYPE_NOTE, noteId, COUNT_TYPE_SHARE);
}
```

分享计数只增不减（不设 UNSHARE），固定映射：
- targetType = 1（NOTE — 维度在笔记
- countType = 4（SHARE）

**消息来源**：`NoteService.shareNote()` 通过 `sendCounterEvent(noteId, "SHARE")` asyncSend 发送。分享次数不设去重（同一用户多次分享同一笔记都算）。

**设计决策 — 为什么只增不减？** 分享是一次性行为，不涉及"取消分享"。业务上也不需要精确的分享计数（近似即可）。这与点赞/收藏/评论不同——它们有 LIKE/UNLIKE 的对称操作。

---

## 6. 类型转换辅助方法 (`CounterEventConsumer.java:171-191`)

```java
private static Integer toInt(Object value) {
    if (value == null) return null;
    if (value instanceof Integer) return (Integer) value;
    if (value instanceof Number) return ((Number) value).intValue();
    try { return Integer.parseInt(value.toString()); }
    catch (NumberFormatException e) { return null; }
}
```

**为什么需要这个？** `ObjectMapper.readValue(json, Map.class)` 将数值反序列化为 Java 类型时：
- 小整数 → `Integer`
- 大整数 → `Long`
- JSON 中没有明确类型标注

因此无法直接强转为 `Integer` 或 `Long`（`ClassCastException` 风险）。`toInt()`/`toLong()` 兼容三种来源：
1. `Integer` / `Long` — 直接返回
2. `Number` — 通过 `.intValue()`/`.longValue()` 转换
3. `String` — 尝试解析（JSON 中 `"123"` 的可能来源）

所有转换失败都返回 null，调用方检查 null 并跳过，不会抛异常。

---

## 7. 跨模块对比：Set-based vs Delta-based 两种计数的全维度分析

| 维度 | Set-based (LIKE/UNLIKE) | Delta-based (FAVORITE/UNFAVORITE) |
|------|------|------|
| **Redis 操作** | SADD/SREM + SCARD | INCRBY/DECR (Lua 去重) |
| **额外存储** | Redis Set `myxhs:like:set:{t}:{id}` | 无（只有一个 counter Key） |
| **MQ 乱序容错** | 完全容错（SADD/SREM 幂等） | 部分容错（归零保护防止负值，但 delta 累加可能偏差） |
| **去重策略** | 同 INCR_WITH_DEDUP_SCRIPT — msgId dedup | 同 INCR_WITH_DEDUP_SCRIPT |
| **DB 对账精度** | SCARD 覆盖后完全正确 | Delta 累加后的值可能有轻微偏移 |
| **内存开销** | Set 内元素数 = 点赞用户数（可达数万） | 单条 String (8 bytes) |
| **适用条件** | 需要 userId，消息有 bizType 区分 | 只需 noteId，固定映射 |

---

## 8. 模块解耦分析

### 8.1 为什么 counter 消费者用 `Map.class` 而非强类型 DTO？

```
counter 模块                    analytics 模块
┌──────────────────┐          ┌──────────────────────┐
│ CounterEventConsumer│         │ LikeEvent.class       │
│ 读 Map<String,Obj> │  ◄─MQ─► │ FavoriteEvent.class   │
│ 不 import analytics │         │                       │
└──────────────────┘          └──────────────────────┘
```

如果 counter import analytics 的 `LikeEvent` 类，会出现：
1. **编译期耦合** — counter 的 pom.xml 需要依赖 analytics 模块
2. **事件演进锁步** — 修改 LikeEvent 字段名会影响两个模块
3. **循环依赖风险** — 如果 analytics 也需要 counter 的接口

使用 `Map.class` 方案（JSON → Map）：
- counter 只依赖字段名（`bizType`、`bizId`、`userId`），不依赖类型
- analytics 可以自由重构 LikeEvent 内部逻辑，只要保持字段名不变

### 8.2 `CounterEvent` DTO — 预留设计

```java
// CounterEvent.java (未实际使用)
public class CounterEvent extends AbstractDomainEvent<CounterEvent> {
    private int targetType;
    private long targetId;
    private int countType;
    private int delta;
}
```

`CounterEvent` 是一个**通用计数事件类**——预留用于未来「非社交行为导致的计数变更」场景（如管理员手动修改计数、数据迁移）。当前 Consumer 直接消费 analytics 的 LikeEvent/FavoriteEvent，不走 CounterEvent。

---

## 9. 工程维度审查

### 9.1 并发安全

| 场景 | 保障 |
|------|------|
| 同 msgId 重复消费 | Lua 原子去重（status=0 跳过） |
| MQ 乱序 (UNLIKE 先, LIKE 后) | Set-based: SREM 空 Set=no-op → 后续 SADD → SCARD=1 ✅ |
| 反序列化异常 | catch Exception → throw RuntimeException → MQ 重试 |
| MDC 残留 | finally 中 `clearTraceContext()` |

### 9.2 可靠性

| 故障 | 影响 | 恢复 |
|------|------|------|
| MQ 重复消息 | 无（去重拦截） | - |
| 消息体字段缺失 | 日志警告 + 跳过（ACK） | 修复 analytics 端重新发送 |
| Redis 不可用 | 消费失败 → maxReconsumeTimes=3 → 死信 | Redis 恢复后人工处理死信 |
| MQ Consumer 重启 | offset 未提交 → 重复消费 | 去重保护 |
| bizType 未知 | log.warn + 跳过 | 修复代码 + 重新部署 |

### 9.3 可观测性

| 维度 | 实现 |
|------|------|
| 日志 | 每条消息 tag/msgId/bizType/处理结果 → `log.info/debug` |
| 链路追踪 | `MqTraceHelper.restoreTraceContext` + SkyWalking agent 自动注入 sw8 |
| 异常告警 | `log.error` → Filebeat → Logstash → ES → Kibana |
| MQ 死信 | RocketMQ 自动投递死信 Topic（`%DLQ%SOCIAL_TOPIC`），控制台可见 |

---

## 10. 面试 Q&A

### Q1: 为什么 Consumer 直接 ACK 而不手动 commit offset？

RocketMQ Spring Starter 的 `RocketMQListener` 接口是 push 模式——方法返回不抛异常 = ACK；抛异常 = 重试。不需要也不支持手动 commit offset。这与 Kafka 的 consumer.commitSync() 不同。

### Q2: 如果反序列化 JSON 抛出异常，Consumer 会无限重试吗？

```java
// CounterEventConsumer.java:73
Map<String, Object> eventMap = objectMapper.readValue(message, Map.class);
```

如果这是一个格式错误的 JSON（如非 UTF-8 编码、JSON 语法错误），会抛出 `JsonProcessingException` → 被 catch (Exception) → throw RuntimeException → RocketMQ 重试 3 次 → 进入死信。**3 次都失败才进死信，不会无限重试**。然后人工排查问题消息。

### Q3: handleLikeEvent 中为什么有 `bizType==2` 但常量只定义了 TARGET_TYPE_NOTE/TARGET_TYPE_USER/TARGET_TYPE_COMMENT，没有显式的 TARGET_TYPE_LIKE？

like 的计数维度是 countType=1（LIKE），而 targetType 表示"谁被点赞"——笔记(1)/用户(2)/评论(3)。bizType=1 表示点赞的是笔记 → targetType=NOTE(1)；bizType=2 表示点赞的是评论 → targetType=COMMENT(3)。bizType 和 targetType 是两个不同的概念：bizType 来自 LikeEvent 的业务分类，targetType 是 counter 模块的目标类型。

### Q4: 如果 analytics 服务修改了 LikeEvent 的字段名（如 `bizType` → `businessType`），会发生什么？

Consumer 端 `eventMap.get("bizType")` 返回 null → `toInt(null)` 返回 null → 日志警告 "缺少必填字段" → ACK → **所有点赞计数停止更新**。

这是一个跨服务契约问题。解决方案：
- 事件 schema 应通过内部文档/契约测试维护
- 或在 counter 模块引入 analytics 的 DTO 依赖（牺牲解耦换取编译时安全）
