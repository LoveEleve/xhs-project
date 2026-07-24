# FollowCounterRepairJob：最终一致性对账

> `FollowCounterRepairJob.java`（XXL-Job）+ `FollowService.repairUserCounters()` + `repairUserRelationships()`

## 为什么需要对账？

因为以下场景会导致 Redis ↔ MySQL 不一致：

| 场景 | Redis | MySQL | 后果 |
|------|:--:|:--:|------|
| follow 时 MySQL INSERT 失败 | ✅ 已写入 | ❌ 漏写 | 关系只存在于 Redis，MySQL 没有备份 |
| unfollow 时 MySQL DELETE 失败 | ✅ 已删除 | ❌ 漏删 | MySQL 有孤儿行 |
| Step B Lua 失败 | 关注者侧 ✅ 目标侧 ❌ | 可能 ✅ | 粉丝 counter 少 1 |

## 对账策略：Redis 是权威，MySQL 是备份

**修复方向永远是单向的：MySQL 必须匹配 Redis**。因为：
- Redis 在读路径上（用户看到的是 Redis 数据）
- Redis ZSet 是实际操作的直接结果（Lua 原子写入）
- MySQL 是异步备份（Consumer 消费 MQ 写入，或自动提交 INSERT）

## 计数对账 — `repairUserCounters()`

```java
public String repairUserCounters(Long userId) {
    Long actualFollowing = opsForZSet().zCard(followingKey);  // Redis ZCARD
    String oldFollowing = opsForValue().get(followingCountKey); // Counter GET

    if (!String.valueOf(actualFollowing).equals(oldFollowing)) {
        opsForValue().set(followingCountKey, String.valueOf(actualFollowing));
        // "关注数修复: 1 → 2"
    }
}
```

**为什么以 ZCARD 为准？** ZCARD 是实时精确的——它统计的是 ZSet 当前有多少 member。而 counter 是 Lua 脚本被动维护的 INCR/DECR 结果——如果 Lua 执行到一半崩溃（Redis OOM），counter 会偏离实际值。ZCARD 永远是正确的。

## 关系对账 — `repairUserRelationships()`

```java
public String repairUserRelationships(Long userId) {
    // 1. Redis ZSet 所有成员
    Set<Long> redisSet = range(followingKey, 0, -1).stream().map(Long::valueOf).toSet();

    // 2. MySQL 所有关注记录
    List<Long> mysqlIds = followMapper.selectFollowUserIdsByUserId(userId);
    Set<Long> mysqlSet = new HashSet<>(mysqlIds);

    // 3. Redis 有、MySQL 无 → INSERT 补上
    for (Long targetId : redisSet) {
        if (!mysqlSet.contains(targetId)) {
            followMapper.insert(new Follow(userId, targetId));
            inserted++;
        }
    }

    // 4. MySQL 有、Redis 无 → DELETE 清理
    for (Long targetId : mysqlSet) {
        if (!redisSet.contains(targetId)) {
            followMapper.deleteByUserIdAndFollowUserId(userId, targetId);
            deleted++;
        }
    }
}
```

## 扫描策略：游标分页

```
lastId = 0
while true:
  userIds = selectDistinctUserIds(lastId, BATCH_SIZE=100)
  if userIds.isEmpty: break

  for each userId:
    repairUserCounters(userId)
    repairUserRelationships(userId)

  lastId = selectMaxIdByLastId(lastId, BATCH_SIZE)
  if lastId <= previousLastId: break
```

**为什么用游标分页而非 `SELECT * LIMIT OFFSET`？** 在对账期间，有新行插入——OFFSET 分页会重复扫描或跳行。游标分页用 `WHERE id > lastId` 保证不重不漏。

**BATCH_SIZE=100 的考量**：每批 100 个用户、每用户 2 次 ZCARD + 2 次 ZRANGE（全量拉回）+ 连接池开销。

## 对账的盲区

如果一个用户的所有 MySQL 记录都因故障丢失了——`selectDistinctUserIds` 扫不到他（因为是根据 MySQL `t_follow` 表扫描的）。这个用户的对账永远不会触发。

**为什么接受这个盲区？** 需要"所有记录全部丢失"——极端情况。SQL 的 INSERT 失败概率本身就远低于 Redis Lua 的部分执行失败。而且即使不对账，Redis 里的数据仍然可用——用户能看到正确的关注列表。

**如何消除盲区？** 改为从 Redis 扫描（遍历 `myxhs:follow:list:*` Key）——但 Redis 没有类似 SQL `DISTINCT` 的扫描方式，成本远高于 MySQL 扫描。

## 生产考虑

| 考虑 | 当前状态 |
|------|:--:|
| 执行频率 | XXL-Job 建议每小时 |
| BATCH_SIZE | 100（可配置） |
| 幂等性 | ✅ INSERT 有 DuplicateKeyException 保护，DELETE 天然幂等 |
| 监控 | 只有 log，无 Prometheus 指标 |
| 并行安全 | 单实例执行（XXL-Job 保证），多实例需分布式锁 |
