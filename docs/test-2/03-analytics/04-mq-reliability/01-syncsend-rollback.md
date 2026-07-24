# syncSend 回滚 vs asyncSend 不阻塞

> LikeService 的 MQ 发送策略（syncSend 回滚） vs FavoriteService 的发送策略（asyncSend 不阻塞）

## 两种策略

```java
// LikeService — syncSend，失败回滚 Redis
if (!sendLikeEventSync(userId, bizType, bizId, "LIKE")) {
    rollbackLikeLua(userId, bizType, bizId);  // 回滚 Redis
    throw new BizException("点赞失败");
}

// FavoriteService — asyncSend + callback
rocketMQTemplate.asyncSend("SOCIAL_TOPIC", event, new SendCallback() {
    onSuccess: log  // 不做任何事
    onException: log // 不做任何事
});
```

| 维度 | syncSend（Like） | asyncSend（Favorite） |
|------|:--:|:--:|
| 返回时机 | 等 Broker 确认（~50ms） | 立即返回（~1ms） |
| 失败处理 | 回滚 Redis | callback 只打日志 |
| 用户体验 | 多等 50ms | 几乎不感知 |
| 一致性 | Redis 和 MQ 同时提交或同时回滚 | Redis 已写入，MQ 可能丢失 |
| 适用场景 | 需要对账一致性 | 允许最终一致 |

## 为什么 Like 需要回滚？

点赞的 Redis 操作是 SADD（Set）+ SADD（反向索引），两个操作在同一个 Lua 脚本中原子执行。如果 Lua 成功但 MQ 发送失败：

```
Redis: like:note:{noteId} = {10001}  ✅
MQ:    SOCIAL_TOPIC tag=LIKE         ❌ Broker 不可达
MySQL: t_like                        ❌ 不会插入（Consumer 没收到消息）
```

用户看到"已点赞"（Redis 有），但 MySQL 没有记录——如果后续 Redis 数据被清理，这条点赞就永久丢失了。

`syncSend` 失败回滚保证了这个场景不会发生。

## 为什么 Favorite 不需要回滚？

收藏只有一个 Key（`myxhs:favorite:{userId}`），ZADD 本身就是幂等操作。如果 MQ 发送失败：

```
Redis: favorite:10001 ZSet = {noteId}  ✅
MQ:    SOCIAL_TOPIC tag=FAVORITE        ❌
MySQL: t_favorite                       ❌
```

但收藏没有计数器需要维护（计数直接用 ZCARD），MySQL 只是备份——Redis 是权威数据源。偶尔丢失一条 MQ 不影响用户看到收藏列表。

这就是 tradeoff：**asyncSend 更快（用户不感知延迟），但接受偶尔的 MQ 丢失**。因为收藏"丢失"的影响远小于点赞（点赞数显示不准 vs 收藏列表少一条）。

## 回滚也有盲区

```java
// LikeService.java 回滚操作
try {
    stringRedisTemplate.execute(unlikeAtomicScript, keys, args);
} catch (Exception e) {
    // 回滚本身也失败了！
    log.error("回滚 Redis 失败");
}
throw new BizException("点赞失败");
```

如果 MQ 发送失败且**回滚也失败**（Redis 在两次操作之间不可达），Redis 中有"幽灵点赞"——SADD 成功但回滚的 SREM 失败。这是个双重故障场景，概率极低。

## 总结

| 策略 | 选择条件 | 代表业务 |
|------|------|:--:|
| syncSend + 回滚 | 有计数器/反向索引需要一致性 | Like |
| syncSend 不回滚 | 有计数器但接受对账修复 | Follow 的 Step B 失败 |
| asyncSend | 无计数器，用 ZCARD/SCARD | Favorite, Follow 的 MySQL INSERT |
