# MQ 事件消费链路深度分析

> 源码：`CounterEventConsumer.java`（177 行）
> 验证：`02-counter-test.md` §2.1 / §2.2

---

## 1. RocketMQ Listener 配置

```java
@RocketMQMessageListener(
    topic = "SOCIAL_TOPIC",
    selectorExpression = "LIKE||UNLIKE||FAVORITE||UNFAVORITE",
    consumerGroup = "counter-consumer-group",
    maxReconsumeTimes = 3
)
public class CounterEventConsumer implements RocketMQListener<MessageExt> { ... }
```

| 配置项 | 值 | 说明 |
|------|------|------|
| `topic` | `SOCIAL_TOPIC` | 与 analytics Producer 发布事件使用同一 Topic |
| `selectorExpression` | `LIKE\|\|UNLIKE\|\|FAVORITE\|\|UNFAVORITE` | 只订阅四种社交事件的 Tag，不消费其他 Tag |
| `consumerGroup` | `counter-consumer-group` | Broker 会为此 Group 记录消费进度，重启后从上次位置继续 |
| `maxReconsumeTimes` | `3` | 消费失败后最多重试 3 次，超过则进入死信队列（DLQ） |

**为什么接受 `MessageExt` 而不是自动反序列化？**

RocketMQ Spring 支持两种模式：
- `RocketMQListener<LikeEvent>` — Spring 自动反序列化 JSON 为 LikeEvent 对象
- `RocketMQListener<MessageExt>` — 拿到原始消息，手动处理

Counter 选择后者，因为 **一个 Listener 消费四种不同的 Tag**（LIKE/UNLIKE/FAVORITE/UNFAVORITE），它们的 JSON 结构不同（LikeEvent 有 `bizType/bizId`，FavoriteEvent 有 `noteId`）。自动反序列化到单一 POJO 类型无法处理这种多态。

---

## 2. 消息解析：为什么用 Map 而不是强类型？

```java
@SuppressWarnings("unchecked")
Map<String, Object> eventMap = objectMapper.readValue(message, Map.class);
```

| 方案 | 优点 | 缺点 |
|------|------|------|
| `Map<String, Object>`（当前） | 与具体 POJO 解耦，analytics 改字段不影响 counter 编译 | 字段名硬编码字符串，无编译期检查 |
| `LikeEvent` + `FavoriteEvent` POJO | 类型安全，IDE 自动补全 | counter 模块必须依赖 analytics 的 POJO 类，两个模块强耦合 |

**当前方案的风险**：`eventMap.get("bizType")` 是字符串硬编码——如果 analytics 把 `bizType` 改成 `businessType`，counter 不会编译报错，只会在运行时取到 null 然后跳过处理（`log.warn("缺少必填字段")`）。

**防御措施**：`log.warn` 记录了缺失字段的信息——如果发布后计数突然不更新，日志中会有 "缺少必填字段" 的 WARN，运维可快速定位。

---

## 3. 事件映射：Tag → 计数值

```java
switch (tag) {
    case "LIKE":     → handleLikeEvent(msgId, eventMap, "LIKE");
    case "UNLIKE":   → handleLikeEvent(msgId, eventMap, "UNLIKE");
    case "FAVORITE": → handleFavoriteEvent(msgId, eventMap, "FAVORITE");
    case "UNFAVORITE":→ handleFavoriteEvent(msgId, eventMap, "UNFAVORITE");
    default:         → log.warn("未知Tag，忽略");
}
```

### 点赞事件（LikeEvent）

```
LikeEvent JSON: { userId, bizType(1=笔记/2=评论), bizId, actionTime }

    LIKE  → incrementWithDedup(msgId, targetType=1, targetId=bizId, countType=1)
    UNLIKE→ decrementWithDedup(msgId, targetType=1, targetId=bizId, countType=1)

    bizType=2（评论）→ 暂不处理
```

### 收藏事件（FavoriteEvent）

```
FavoriteEvent JSON: { userId, noteId, actionTime }

    FAVORITE → incrementWithDedup(msgId, targetType=1, targetId=noteId, countType=2)
    UNFAVORITE→ decrementWithDedup(msgId, targetType=1, targetId=noteId, countType=2)

    收藏只针对笔记，没有变体类型
```

**bizType=2（评论点赞）的预留**：analytics 可以发布 `bizType=2` 的 LIKE 事件，但 counter 暂不处理。这是设计上的预留——如果未来需要统计评论点赞数，只需在 `handleLikeEvent` 中加一个 `else if (bizType == 2)` 分支，改动范围可控。

---

## 4. 幂等保护：msgId 去重的完整链路

```java
boolean executed = counterService.incrementWithDedup(msgId, targetType, bizId, countType);
// executed = true  → 首次处理，Redis INCR + Buffer.add
// executed = false → 重复消息，dedup Key 已存在，静默跳过
```

**完整调用链**：

```
CounterEventConsumer.onMessage(msg)
  ├─ MqTraceHelper.restoreTraceContext(msg)  // 恢复 TraceId
  ├─ String msgId = msg.getMsgId()           // 获取 RocketMQ 消息 ID
  ├─ handleLikeEvent(msgId, eventMap, tag)
  │   └─ CounterService.incrementWithDedup(msgId, ...)
  │       └─ Lua 脚本:
  │           EXISTS(dedupKey) == 1 → return [0, 0]  // 去重拦截
  │           SET dedupKey = "1" EX 7200                // 设去重标记
  │           INCRBY counterKey +1                      // 原子递增
  │           return [1, newCount]
  └─ MqTraceHelper.clearTraceContext()         // 清理
```

**三层返回值语义**：

| Lua status | 含义 | Consumer 行为 | MQ ACK |
|:--:|------|------|:--:|
| `1` | 首次处理成功 | log "点赞计数更新" | ✅ 正常 ACK |
| `0` | 重复消息（dedup Key 存在） | log "去重跳过" | ✅ 静默 ACK |
| `-1` | 归零保护触发 | log "去重跳过"（也是 false） | ✅ 正常 ACK |

所有三种情况都不抛异常 → MQ 正常 ACK → 不会触发重试。这是关键设计——只有 Redis 真正的异常（连接失败、Lua 脚本执行失败）才抛 `RuntimeException` 触发重试。

---

## 5. 异常处理与重试

```java
try {
    // ... 消息解析 + 事件分发
} catch (Exception e) {
    log.error("[计数Consumer] 消费失败: msgId={}", msgId, e);
    throw new RuntimeException("计数消息消费失败，触发重试", e);
} finally {
    MqTraceHelper.clearTraceContext();
}
```

**重试策略**：

RocketMQ push consumer 的重试延迟由 Broker 的 `messageDelayLevel` 配置决定。默认值：

| 重试次数 | 延迟 | 延迟级别 |
|:--:|------|:--:|
| 第 1 次 | ~1s | level 1 |
| 第 2 次 | ~5s | level 2 |
| 第 3 次 | ~10s | level 3 |
| 超过 3 次 | — | 进入 DLQ |

**重试窗口与 dedup TTL 的关系**：dedup Key 的 TTL 是 2 小时（7200s），远超重试窗口（1s + 5s + 10s = 16s）。即使消息在重试后才真正消费成功，dedup Key 仍然有效——后续的重复投递会被拦截。

**finally 块的重要性**：`MqTraceHelper.clearTraceContext()` 在 finally 块中，保证无论消费成功还是失败，TraceId 都会被清理——避免 TraceId 泄漏到下一个消息的消费线程中。RocketMQ Spring 的消费线程是复用的，不清理会导致 TraceId 跨消息污染。

---

## 6. TraceId 跨进程传播

```
analytics (Producer)                        counter (Consumer)
  ┌─────────────────┐                      ┌─────────────────────┐
  │ Tracer.生成       │                      │ MqTraceHelper       │
  │ TraceId: abc123  │  RocketMQ Message   │ .restoreTraceContext │
  │ .setTraceId()    │  userProperties =   │ (msg)               │
  │                  │  {"traceId":"abc123"}│   → MDC.put(        │
  │ send(msg) ──────→│─────────────────────→│     "traceId","abc")│
  └─────────────────┘                      │                     │
                                           │ log.info("...")     │
                                           │   → [abc123]        │ ← 同一 TraceId
                                           │ .clearTraceContext()│
                                           └─────────────────────┘
```

analytics 在发送 MQ 之前将 TraceId 写入 `Message.userProperties`，counter 在消费时通过 `MqTraceHelper.restoreTraceContext(msg)` 恢复到 `MDC`。这样 counter 的日志和 analytics 的日志共享同一个 TraceId，全链路可追踪。

---

## 7. 类型安全转换：toInt / toLong

```java
private static Long toLong(Object value) {
    if (value == null) return null;
    if (value instanceof Long) return (Long) value;
    if (value instanceof Number) return ((Number) value).longValue();
    try {
        return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
        return null;
    }
}
```

`objectMapper.readValue(message, Map.class)` 反序列化 JSON 时：
- 小整数（如 `bizType: 1`）→ Jackson 默认映射为 `Integer`
- 大整数（如 `noteId: 2076147855673843713`）→ Jackson 映射为 `Long`（超出 Integer 范围自动升级）

如果直接用 `(Long) eventMap.get("bizType")` → ClassCastException（因为 Jackson 映射为 Integer）。

`toInt/toLong` 的多态处理（`instanceof Integer/Long/Number` + `parseLong(toString)`）覆盖了所有可能性。

---

## 8. 与 analytics 模块 Consumer 的对比

| 维度 | counter `CounterEventConsumer` | analytics `LikeConsumer` |
|------|------|------|
| 消息源 | 仅被动消费 | 仅被动消费 |
| 消息格式 | 通用 `Map<String, Object>`（手动 parse） | 强类型 `LikeEvent` POJO（`objectMapper.readValue(msg, LikeEvent.class)`） |
| 处理逻辑 | 纯计数操作（Redis → Buffer） | 关系维护（Redis Set 存储点赞记录） |
| 幂等策略 | msgId Lua 去重 | Redis SADD 幂等（已存在返回 0） |
| 重试 | 最多 3 次，进入 DLQ | 最多 3 次 |
| 复杂性 | 低（单向计数增减） | 中（Set 集合操作，双向查询） |

counter 和 analytics 的 Consumer 都是独立消费同一 Topic 的不同 Tag——analytics 负责维护"哪些用户点赞了"这种关系数据，counter 负责维护"点赞总数"这个聚合值。Producer 是 `LikeController.like()`（analytics 的 HTTP 端点），不是任何一个 Consumer。

---

## 9. DLQ 死信队列监控

common 模块的 `DlqMetrics` 会为每个 Consumer Group 创建 DLQ 监控：

```
[DLQ监控] 创建 PullConsumer: DLQ_MONITOR_counter-consumer-group
```

当消息重试 3 次仍失败后进入 DLQ，监控线程定期拉取 DLQ 队列深度。如果 DLQ 堆积（说明有消息持续消费失败），`DlqMetrics` 会通过 `BusinessMetrics` 上报到 Micrometer → Prometheus → 触发告警。

---

## 关联文档

- `01-counter-module.md` — §6 MQ Consumer
- `02-counter-test.md` — §2.1 MQ 消费 / §2.2 去重拦截
- `03-buffer-trigger.md` — CounterBuffer（Consumer 写入的下游）
- `07-data-consistency.md` — 三层一致性（Consumer 是第一层入口）
