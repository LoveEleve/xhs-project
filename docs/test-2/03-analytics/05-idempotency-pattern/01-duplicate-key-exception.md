# DuplicateKeyException 幂等模式

> 4 个 Consumer 的统一幂等策略 + FollowConsumer 为什么默认禁用 + createdAt 语义修正

## 为什么不校验 eventId？

`AbstractDomainEvent` 自动生成了 `eventId = UUID.randomUUID()`，但 Consumer 完全不看这个字段——幂等全靠 MySQL 唯一索引：

```java
// 所有 4 个 INSERT Consumer 使用同一模式
try {
    mapper.insert(entity);
} catch (DuplicateKeyException e) {
    log.debug("重复消费，幂等忽略");
}
```

| Consumer | 唯一索引 | 字段 |
|---------|------|------|
| LikeConsumer | `uk_user_biz` | (user_id, biz_type, biz_id) |
| FavoriteConsumer | `uk_user_note` | (user_id, note_id) |
| FollowConsumer | `uk_user_follow` | (user_id, follow_user_id) |

**为什么用 DB 唯一索引而不是 eventId 去重？**

1. 如果 Redis 中记录了一条 `SET myxhs:like:mq:{eventId} = 1` 来做幂等——多一次 Redis 调用
2. 如果 eventId 记录过期了（TTL 到），重复消息会绕过幂等检查
3. DB 唯一索引是**永久有效**的——不会过期，不需要维护
4. DuplicateKeyException 本身就是 MySQL 的防御机制——免费的

**代价**：每次重复消费都会执行 INSERT → MySQL 尝试插入 → 检测唯一索引冲突 → 抛异常 → catch 吞掉。虽然最后结果正确，但产生了一次无意义的 DB 往返。

## createdAt 语义不一致

| Consumer | createdAt 来源 | 语义 |
|---------|------|------|
| LikeConsumer | `event.actionTime` → `LocalDateTime.ofInstant(Instant.ofEpochMilli(actionTime))` | 事件发生时刻 ✅ |
| FavoriteConsumer | `LocalDateTime.now()` | Consumer 消费时刻 |
| FollowConsumer | `event.actionTime` → `LocalDateTime.ofInstant(...)` | 事件发生时刻 ✅ |

Like 已通过 `actionTime` 字段统一（修复前是 `now()`），但 Favorite 仍然是 `now()`。

**MQ 积压 10 分钟时**：Favorite 的 `createdAt` 比实际收藏时间晚 10 分钟。影响：如果按时间排序收藏列表，积压期间的收藏顺序会错乱。

## FollowConsumer 为什么默认禁用

```java
@ConditionalOnProperty(name = "myxhs.mq.follow-consumer.enabled", havingValue = "true")
public class FollowConsumer { ... }
```

默认不激活，因为 FollowService 当前使用**同步 INSERT**写入 MySQL——不走 MQ。如果启用 Consumer，MQ 消息和同步 INSERT 会**同时**到达 MySQL——造成重复写入。DuplicateKeyException 虽然能防重复，但多了一次无意义的 DB 往返。

**什么时再启用？** 当 FollowService 改为"Redis Lua → asyncSend MQ → Consumer 落库"的异步模式时，启用这两个 Consumer 接管 DB 写入。

## Tag 与 payload.action 无交叉校验

所有 Consumer 完全依赖 MQ Tag 路由，不检查 Event 中的 `action` 字段：

```java
// LikeConsumer — 只看 Tag = LIKE，不检查 event.getAction()
// 如果 MQ Tag=LIKE 但 event.action="UNLIKE" → 照样 INSERT
```

**当前状态**：Tag 和 action 由同一段代码设置（`LikeService.sendLikeEventSync(String action)` 同时传 Tag 和 action），理论上不会出现不一致。但如果将来 Tag 路由逻辑变更，这个隐患就会暴露。

**修复**：Consumer 中加一行 `if (!"LIKE".equals(event.getAction())) log.warn(...)`——成本极低，但能给故障排查提供关键线索。
