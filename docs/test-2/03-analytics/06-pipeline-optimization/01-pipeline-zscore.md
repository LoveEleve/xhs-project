# Pipeline 批量优化：一次网络往返替代 N 次 ZSCORE

> `FollowService.getFollowingList()` 和 `getFollowerList()` 中的 `executePipelined`

## 问题：N+1 查询

关注列表的 FollowVO 需要返回 `isFollowBack` 字段——"我关注的这个人，是否也关注了我？"

最直观的写法——对列表中的每个用户查一次 ZSCORE：

```java
for (FollowVO vo : followings) {
    Double score = opsForZSet().score(myFollowerKey, vo.getUserId());
    vo.setIsFollowBack(score != null);  // score 不为 null = 互关
}
```

每页 20 个用户 = 20 次 `ZSCORE` = **20 次网络往返** ≈ 20ms。

当用户翻到第 5 页时，每次刷新都需要 20ms 的 Redis 开销。

## 解决：executePipelined

```java
// FollowService.java:212-221
List<Object> pipelineResults = stringRedisTemplate.executePipelined(
    (RedisCallback<Object>) connection -> {
        byte[] keyBytes = myFollowerKey.getBytes(StandardCharsets.UTF_8);
        for (String targetId : targetUserIds) {
            connection.zSetCommands().zScore(keyBytes, targetId.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }
);

// 结果映射：pipelineResults[i] ≠ null → 互关
for (int i = 0; i < tupleList.size(); i++) {
    vo.setIsFollowBack(pipelineResults.get(i) != null);
}
```

**`executePipelined` 做了什么？**

正常模式：
```
客户端 → ZSCORE key user1 → Redis
客户端 ← score1
客户端 → ZSCORE key user2 → Redis
客户端 ← score2
...
20 次往返
```

Pipeline 模式：
```
客户端 → ZSCORE key user1
         ZSCORE key user2
         ZSCORE key user3
         ...
         ZSCORE key user20        ← 一次性发送 20 条命令
         ← score1
         ← score2                  ← 一次性接收 20 条结果
         ...
20 次命令，1 次网络往返
```

**为什么不是 batch ZSCORE？** Redis 没有 `MGET` 那种 ZSet 批量 score 查询命令——`ZSCORE` 一次只能查一个 member。但 Pipeline 在协议层面一次性打包发送 20 个 `ZSCORE`，Redi 依次执行后一次性返回——效果等效于"批量"，但没有引入新命令。

## 性能对比

| 方式 | 网络往返 | 延迟（每页20条） |
|------|:--:|:--:|
| 逐条 ZSCORE | 20 次 | ~20ms |
| Pipeline ZSCORE | **1 次** | ~2ms |
| 不用 Pipeline | 翻页翻到第 5 页 = 额外 100ms | **用户感知明显** |

节省的不是计算时间——Redis 处理 20 个 ZSCORE 本来就是毫秒级。节省的是**网络往返的累积延迟**。

## 为什么还在关注列表和粉丝列表两处都用了？

关注列表（`getFollowingList`）：我需要知道"我关注的人有没有关注我" → 查我的粉丝列表
粉丝列表（`getFollowerList`）：我需要知道"关注我的人，我有没有也关注他" → 查我的关注列表

两个方向的互关判定都需要 Pipeline——因为都是 per-item 的 ZSCORE 查询。

## 与 N+1 SQL 问题的类比

这是 Redis 层面的 N+1 问题——和 SQL 中 `for each user { query }` 的本质相同。SQL 的解决方案是 JOIN，Redis 的解决方案是 Pipeline。两者都通过**减少往返**来优化，而不是减少计算。
