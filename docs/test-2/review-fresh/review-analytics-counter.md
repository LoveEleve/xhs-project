# Analytics / Counter 模块 Review

## Counter（计数服务）
1. **成熟度很高**：Lua 原子去重+增减 / Set-based Like 计数（天然解决 MQ 乱序）+ 归零保护 +
   Buffer 攒批刷盘 + 双向对账（Redis↔DB + analytics 权威修正）。防超卖式计数设计扎实。
2. **[中] 计数 Redis Key 永久无 TTL** (CounterService.java:getCount:406, increment:145)
   每个 targetId×countType 一个永久 key，笔记/评论持续增长 → Redis 内存无界。
   建议对冷计数设 TTL + 读时重建，或依赖对账清冷数据。

3. **[中] like 双数据源复杂度**：analytics 权威 Set + counter 展示 Set，靠懒迁移 + 对账收敛。
   逻辑正确但耦合 analytics，模块间共享 Redis key 语义需严格维护（跨模块耦合点）。

## Analytics（社交：点赞/关注/收藏）
4. 依赖 Redis Set（权威）+ MQ 事件 + 对账修复（FollowCounterRepairJob 等），整体成熟。
5. [未逐行深读 LikeService/FollowService/FavoriteService 主体，但从消费者/Job 结构看模式统一]。
   建议对社交"点赞状态批量查询 + 计数"做专项性能复核（home feed 依赖其 batchCheckLikeStatus）。

## 备注
- 此两模块是本项目里分布式一致性处理最稳的部分之一。
