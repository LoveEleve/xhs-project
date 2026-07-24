# 同一模块三数据结构共存：Set vs ZSet vs Counter

> 为什么 analytics 模块同时使用 Set、ZSet、String counter 三种 Redis 数据类型

## 快速对照

| 业务 | 结构 | 操作 | 时序需求 | 为什么不用更强的结构 |
|------|:--:|------|:--:|------|
| Like 正向 | **Set** | SADD SREM | 无 | 省内存，不需要排序 |
| Like 反向 | **Set** | SADD SREM | 无 | 同上 |
| Follow 关系 | **ZSet** | ZADD ZREM | score=时间 | 必须按时间排序分页 |
| Favorite 列表 | **ZSet** | ZADD ZREM | score=时间 | 必须按时间排序分页 |
| Follow 计数 | **String** | INCR DECR | 无 | Lua 脚本内需要读写 |

## 为什么 Like 不用 ZSet？

ZSet 的每个 member 需要额外存储 8 字节 score。对于点赞功能：
- "某篇笔记被谁点赞了"只需要知道**谁**（判存在），不需要知道**什么时候点赞的**
- 前端展示点赞列表：通常只展示最近几个头像，后端只返回 user_ids，不需要时间排序

如果用 ZSet：
```
ZSet: 64KB（1000 个点赞 × 16 字节 = 16KB + 48KB overhead 估算）
Set:  48KB（1000 个点赞 × 8 字节 = 8KB）
节省 ~30% 内存
```

对于 1000 篇笔记各 1000 点赞：
```
ZSet: 1000 × 16KB = 16MB
Set:  1000 × 8KB  = 8MB
节省 8MB Redis 内存
```

如果以后需要"按点赞时间排序"的功能，Set 迁 ZSet 成本很低——改 1 行 `opsForSet().add()` → `opsForZSet().add(value, timestamp)`。

## 为什么 Follow 不能用 Set？

关注列表的核心功能是"按关注时间倒序展示"——Set 不支持排序。如果用 Set + 额外的 `List` 存储时间：
- 两个 Key 需要原子更新 → 必须用 Lua
- 数据冗余：同样一份关系存了两份
- 查询成本：需要先查 Set 判存在，再查 List 取时间

ZSet 的 `score=timestamp` 一步解决——存储、排序、分页都在一个 ZREVRANGE 调用中完成。

## 为什么 Favorite 不用 ZSet 存 member id + score 吗？

误解：Favorite 确实用的是 ZSet——`myxhs:favorite:{userId}` 就是 ZSet，score = 收藏时间。但不需要像 Follow 那样双向存储（不需要查询"这篇笔记被谁收藏了"），所以只维护一个 Key。

## 为什么 Like 有反向索引但 Favorite 没有？

反向索引 = 从"点赞过的笔记"查询：

```
myxhs:like:user:10001:note = {note1, note2, note3}
```

需求来源：用户在"我的点赞"页面看到自己点赞过的所有笔记。Favorite 用 ZSet 天然支持这个需求（`ZREVRANGE` 按 score 倒序），不需要额外索引。

## 内存对比总结

| 结构 | 单 member 成本 | 应用场景 |
|------|:--:|------|
| Set | 8 字节 | Like（只需判存在） |
| ZSet | 16 字节 | Follow/Favorite（需要时间排序） |
| String counter | 约 30 字节 overhead | 关注数/粉丝数（Lua 内读写） |

每种结构都是针对特定需求的最优解——不是"应该统一用 ZSet"，而是"不同需求应该用不同的结构"。
