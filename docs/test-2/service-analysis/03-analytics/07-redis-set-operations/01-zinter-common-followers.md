# ZINTER 服务端交集：共同关注的正确做法

> `FollowService.getCommonFollowing()` 中的 `opsForZSet().intersect()`

## 问题：客户端求交集的陷阱

求"我和另一个用户都关注了谁"——最直观的做法：

```java
// ❌ 坏方案：拉回所有数据后在内存中求交集
Set<String> myFollowing = opsForZSet().range(myKey, 0, -1);      // 5000 条
Set<String> targetFollowing = opsForZSet().range(targetKey, 0, -1); // 5000 条
myFollowing.retainAll(targetFollowing);  // 内存中求交集
```

如果两个用户各关注了 5000 人，`range(0, -1)` 拉回全部数据 = 10000 × 8 字节（userId）= ~80KB。加上 ZSet score 和 Java Set 的内存开销，实际内存占用远大于此。

更致命的是——**大 V 的关注数可远超 5000**。如果 10 万关注，`range(0, -1)` = 800KB 网络传输 + MB 级内存拷贝。

## 解决：ZINTER

```java
// FollowService.java:320-322
Set<String> common = stringRedisTemplate.opsForZSet()
        .intersect(myKey, targetKey);
```

`ZINTER` 在 **Redis 服务端** 完成交集计算——只把**结果**（通常很小——两个用户的共同关注最多几十个）传回客户端。

## 执行过程

```
客户端 → ZINTER 2 myKey targetKey
Redis 服务端：
  1. 取出 myKey 的 ZSet 成员集合
  2. 取出 targetKey 的 ZSet 成员集合
  3. 求交集
  4. 返回交集成员列表
客户端 ← [共同关注的 userId]
```

对比：

| 方案 | 客户端网络传输 | Redis 内存 | 客户端内存 |
|------|:--:|:--:|:--:|
| 拉回求交集 | 10000 条 | 零 | 10000 条 Set |
| ZINTER | **10-50 条** | 临时计算 | 10-50 条 Set |

## 为什么注释写 "【m19】使用 ZINTER 服务端求交集"？

`m19` 是修复编号——说明最初实现确实是客户端拉回求交集，后来发现了大数据量问题才修复为 ZINTER。代码注释说"避免 5000×2 数据拉回内存"——直接点明了原方案的问题。

## 上限保护——为什么还有 `limit(MAX_COMMON_FOLLOW_FETCH)`？

```java
return common.stream()
        .map(Long::valueOf)
        .limit(MAX_COMMON_FOLLOW_FETCH)  // ← 二次保护
        .collect(Collectors.toList());
```

ZINTER 虽然避免了全量拉回，但如果两个用户都是超级大 V（各关注 10 万人），交集也可能很大（比如同属一个圈子）。`limit()` 是第二道防线——只返回前 N 个共同关注，防止返回值过大导致序列化超时或前端渲染卡顿。

## 其他可以优化的 Set 操作

| 场景 | 当前实现 | Redis 命令 |
|------|---------|-----------|
| 共同关注 | ZINTER | ✅ 已修复 |
| 关注列表页 | ZREVRANGE + Pipeline ZSCORE | ✅ Pipeline |
| 粉丝列表页 | ZREVRANGE + Pipeline ZSCORE | ✅ Pipeline |
| Like 批量状态查询 | Pipeline SISMEMBER | ✅ LikeController |
| Favorite 列表 | ZREVRANGE | ✅ 单 Key，天然高效 |
