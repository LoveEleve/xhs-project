# my-xhs-counter 模块深度分析

## 1. 当前模块定位

counter（19004）是计数服务，提供点赞/收藏/评论/分享/浏览/粉丝/关注等计数。消费 `SOCIAL_TOPIC` 事件，用 Redis 实时计数 + Buffer 攒批刷盘 MySQL，支持单查/批量查询、去重幂等、归零保护与对账修复。它是社交行为的计数聚合层，被 content/home 等查询计数依赖。

## 2. 当前代码事实

- 启动入口 `CounterApplication`；Controller：CounterController（get/batch-get/reconcile）。
- 核心：CounterService（595 行）、CounterBuffer、CounterEventConsumer、CounterReconcileJob、CounterMapper。
- 数据：MySQL `t_counter(target_type, target_id, count_type, count_value)`；Redis 计数 key `myxhs:counter:{type}:{id}:{countType}`。

## 3. 关键业务链路与源码流转

### 3.1 写链路

```text
SOCIAL_TOPIC(LIKE/UNLIKE/FAVORITE/UNFAVORITE/COMMENT/UNCOMMENT/SHARE/VIEW/FOLLOW/UNFOLLOW)
→ CounterEventConsumer（tag 分发）
→ CounterService
  - 点赞：setBasedLikeWithDedup（Set 抗乱序）
  - 其他：incrementWithDedup/decrementWithDedup（dedup Lua）
→ Redis 计数 key 实时更新
→ CounterBuffer 攒批 → 批量刷盘 t_counter
```

### 3.2 关键设计

- **Set-based 点赞计数**：counter 内 `myxhs:like:set:{type}:{id}` 独立 Set，SCARD 为计数；SADD/SREM 天然幂等，乱序到达（UNLIKE 先/LIKE 后）不影响最终计数。
- **MQ 去重幂等**：`myxhs:counter:dedup:{msgId}`（2h TTL）+ 计数增减在同一 Lua 原子执行，消除竞态窗口；归零保护（status=-1）时计数不重试。
- **Buffer 刷盘**：Redis 实时值 + 攒批异步写 DB，失败重试 3 次，凌晨对账兜底。
- **归零保护**：DECR 前 Lua 检查当前值，防止计数为负。
- **对账修复**：以 DB 扫描对比 Redis（Redis=0/DB>0 恢复 Redis，否则修正 DB）；并从 analytics 权威 Set 修正 like 计数并同步 DB（T-113）。
- **懒迁移**：counter Set 为空但计数>0 时，从 analytics 权威 Set 同步成员。

## 4. 数据流转与中间件参与点

| 中间件 | key/表/topic | 一致性边界 |
|---|---|---|
| Redis | `myxhs:counter:{type}:{id}:{countType}` | 实时权威 |
| Redis | `myxhs:counter:dedup:{msgId}` | MQ 幂等（2h） |
| Redis | `myxhs:like:set:{type}:{id}` | 点赞展示 Set（抗乱序） |
| Redis | `myxhs:counter:user_following/follower:{uid}` | 关注计数 |
| MySQL | `t_counter` | Buffer 刷盘持久化 |
| MQ | SOCIAL_TOPIC 10 种 tag | 消费计数 |

## 5. 跨模块与分布式行为

- 消费 analytics（LIKE/UNLIKE/FAVORITE/UNFAVORITE/FOLLOW/UNFOLLOW）与 content（COMMENT/UNCOMMENT/SHARE/VIEW）事件。
- 查询供 content/home 等 Feign 使用。
- 点赞 Set 与 analytics 权威 Set 可能漂移，靠懒迁移 + 对账收敛。

## 6. 性能与工程质量

- Pipeline 批量读（batchGetCounts），避免 N+1。
- Lua 脚本静态复用（EVALSHA）。
- 计数 key 续期 30 天，冷 key 自动回收。
- 归零保护、去重、对账三重一致性保障。

## 7. 鉴权基础检查

- get/batch-get 走公开/JWT；reconcile 为内部/管理调用。

## 8. 当前分支/近期改动点

- dedup 路径补 30 天续期（O-Counter-1）、Set-based 点赞（H2）、级联删除 delta（O-Counter-2）、对账同步 DB（T-113）。

## 9. 风险与测试重点

- 代码：Buffer 刷盘失败重试与对账兜底。
- 业务：乱序到达、重复消息、归零保护、懒迁移边界。
- 分布式：analytics 权威 Set 与 counter Set 漂移。
- 可观测：dedup/归零/对账修复日志与指标。

## 10. 覆盖对账

核心 CounterService/CounterEventConsumer/CounterController/CounterBuffer 已深读；实体/Mapper/DTO 随调用链核对。
