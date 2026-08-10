# ZSet 关注关系设计：双向存储 + Pipeline 互关

> 关注列表/粉丝列表/计数器的完整 Redis 数据模型

## 双向存储——为什么不是一张表？

最简单的方案——只存一份关系：

```
myxhs:follow:10001 = [2078387513547841537]  // 10001 关注了谁
```

但要查"谁关注了 2078387513547841537"（粉丝列表）怎么办？只能全表扫描。所以需要双向存储：

```
关注者→被关注者：myxhs:follow:list:{userId}     ZSet
被关注者→关注者：myxhs:follow:fans:{userId}     ZSet
```

**代价**：每次关注/取关需要同时更新两个 Key。这就是为什么 follow 必须拆成两个 Lua 脚本——两个 Key 属于不同用户。

## 为什么用 ZSet 而非 Set？

| 需求 | Set | ZSet |
|------|:--:|:--:|
| 判存在 | ✅ SISMEMBER | ✅ ZSCORE |
| 计数 | ✅ SCARD | ✅ ZCARD |
| 按关注时间排序 | ❌ 无序 | ✅ ZREVRANGE 按 score 倒序 |
| 分页 | ❌ 不支持 | ✅ ZREVRANGE with start/end |
| 关注时间展示 | ❌ 无法存储 | ✅ score = 时间戳 |

**关注列表必须按时间排序**——"最近关注了谁"是核心功能。Set 做不到。

score = `System.currentTimeMillis()`，毫秒精度的时间戳，既满足排序需求，又天然记录了关注时间。

## 互关判定——为什么需要额外的 Pipeline？

```
我关注了 [A, B, C, D, E]
我要知道这 5 个人中，谁关注了我（互关）

逐条查：ZSCORE myxhs:follow:fans:10001 A → null（没关注我）
         ZSCORE myxhs:follow:fans:10001 B → 1234567890（关注了我）
         ... 5 次往返

Pipeline：一次性发送 5 个 ZSCORE，一次往返拿回 5 个结果
```

Pipeline 把 N 次往返变成 1 次——详见 `06-pipeline-optimization/`。

## 计数器：ZCARD vs 独立 counter

关注数/粉丝数有两个来源：

| 来源 | 方式 | 精度 |
|------|------|:--:|
| ZCARD | 实时统计 ZSet 成员数 | 永远精确 |
| counter String | Lua 脚本中 INCR/DECR 维护 | 可能偏离（Lua 部分失败） |

**为什么维护独立的 counter 而不是直接用 ZCARD？** Lua 脚本执行中需要返回值反馈——`follow_self.lua` 的返回是 1/0（成功/幂等），不是关注的计数。counter 在脚本内 INCR，让调用方能通过 `GET counter` 快速拿到关注数——不需要再单独调一次 ZCARD。

**为什么 ZCARD 作为对账基准？** 因为 ZCARD 是 Redis 原生命令，不受 Lua 部分失败的影响。counter 是应用层维护的辅助变量，可能偏离。对账时以 ZCARD 为准——见 `08-counter-repair/`。

## 数据量估算

假设每个用户平均关注 200 人：

- ZSet 每 member 存储：8 字节（member long） + 8 字节（score double） ≈ 16 字节
- 200 members × 16 = 3.2KB per user
- 双向存储：3.2KB × 2 × 100 万用户 ≈ 6.4GB

相比 MySQL 存储（`BIGINT + BIGINT + DATETIME + 索引` ≈ 60 字节/行），Redis 约便宜 4 倍，且读取速度快 2 个数量级。
