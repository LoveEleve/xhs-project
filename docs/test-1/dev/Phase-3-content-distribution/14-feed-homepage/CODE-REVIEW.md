# 14-Feed 流首页 Code Review

> 模块：my-xhs-home | 端口：9015 | 审查时间：2026-05-14

---

## 📊 一、P8 评分表

| 维度 | 满分 | 得分 | 评价 |
|------|:----:|:----:|------|
| 架构设计 | 20 | 18 | 推拉混合模型设计合理，BFF 聚合层职责清晰，无 DB 依赖 |
| 并发安全 | 20 | 17 | CompletableFuture 并行聚合 + 超时降级，分布式锁保护定时任务；但 batchGetNoteDetails 内部嵌套 CompletableFuture 有线程池饥饿风险 |
| 性能优化 | 15 | 14 | 游标分页避免深翻页、Pipeline 批量写入、MGET 批量查询大V标记；线程池参数可配置 |
| 容错降级 | 15 | 13 | Feign FallbackFactory + CompletableFuture.getNow() 降级；但缺少 Circuit Breaker 和限流保护 |
| 代码质量 | 10 | 9 | 注释详尽、职责单一、命名规范；少量 Map<String, Object> 弱类型可改进 |
| 可观测性 | 10 | 8 | MDC TraceId 透传到线程池、关键路径日志完整；缺少 Metrics 埋点（聚合耗时、降级次数） |
| 分布式考量 | 10 | 9 | 定时任务 Redisson 分布式锁、ZADD 天然幂等、SCAN 遍历避免阻塞 |
| **总分** | **100** | **88** | **优秀（P7+ 水平，接近 P8）** |

---

## 🔍 二、问题发现与修复建议

### 问题 1：batchGetNoteDetails 嵌套 CompletableFuture 导致线程池饥饿风险 ⚠️

**位置**：`FeedService.java` L220-250

**问题**：`aggregateFeed()` 方法在 `aggregatorPool` 中提交了第 1 层任务（notesFuture），而 `batchGetNoteDetails()` 内部又在同一个 `aggregatorPool` 中为每个 noteId 提交了子任务。当并发请求多时，外层任务占满线程池后，内层子任务无法获得线程执行，形成**死锁/饥饿**。

**影响**：高并发下 Feed 接口超时，线程池所有线程互相等待。

**修复建议**：
```java
// 方案 A：batchGetNoteDetails 内部使用独立线程池
@Bean("batchFeignPool")
public ExecutorService batchFeignPool() { ... }

// 方案 B：外层 aggregateFeed 不在 aggregatorPool 中执行（当前已是 Tomcat 线程调用）
// 只有内部的 CompletableFuture.supplyAsync 使用 aggregatorPool
// 当前代码实际上是方案 B（外层在 Tomcat 线程），但 batchGetNoteDetails 
// 被 notesFuture 包裹后又在 aggregatorPool 中执行，形成嵌套
```

**当前代码分析**：
- `notesFuture = CompletableFuture.supplyAsync(() -> batchGetNoteDetails(noteIds), aggregatorPool)` — 外层在 aggregatorPool
- `batchGetNoteDetails` 内部又 `CompletableFuture.supplyAsync(..., aggregatorPool)` — 嵌套在同一池

**正确做法**：去掉外层包装，`batchGetNoteDetails` 本身已经是并行的，不需要再包一层 CompletableFuture：
```java
// 修复前
CompletableFuture<Map<Long, Map<String, Object>>> notesFuture = CompletableFuture
    .supplyAsync(() -> batchGetNoteDetails(noteIds), aggregatorPool);

// 修复后：直接在 Tomcat 线程中调用（内部已并行），或使用独立池
CompletableFuture<Map<Long, Map<String, Object>>> notesFuture = CompletableFuture
    .supplyAsync(() -> batchGetNoteDetails(noteIds), aggregatorPool);
// 但 batchGetNoteDetails 内部改用 ForkJoinPool.commonPool() 或独立池
```

**严重程度**：🟡 中（线程池 50 线程 + 200 队列，低并发不会触发，但高并发必现）

---

### 问题 2：游标分页精度丢失 ⚠️

**位置**：`FeedService.java` L80

**问题**：`lastScore - 0.001` 作为游标边界，当两条笔记的 publishTime 相同时（毫秒级时间戳），会漏掉数据。

**分析**：
- Redis ZSet score 是 double 类型，存储的是毫秒时间戳（如 1715692800000）
- `lastScore - 0.001` 在这个量级下精度足够（double 在 10^12 量级有 4-5 位小数精度）
- 但如果两条笔记在同一毫秒发布，`reverseRangeByScoreWithScores(key, 0, lastScore - 0.001, 0, size)` 会把同 score 的都排除

**修复建议**：使用 `(lastScore, +inf)` 开区间 + 额外记录 lastNoteId 做去重：
```java
// 游标 = score + noteId 组合
// 查询时：score <= lastScore 且排除 lastNoteId
```

**严重程度**：🟢 低（同毫秒发布概率极低，且不影响数据完整性，只是极端情况漏 1-2 条）

---

### 问题 3：收件箱无容量上限保护 ⚠️

**位置**：`FeedPushConsumer.java` L93-110

**问题**：推模式写入粉丝收件箱时，只设置了 TTL（7 天过期），但没有限制 ZSet 的最大元素数。如果某用户关注了大量活跃普通用户，7 天内收件箱可能积累数万条数据。

**修复建议**：推送后执行 `ZREMRANGEBYRANK` 保留最新 N 条：
```java
// 推送后裁剪（保留最新 500 条）
stringRedisTemplate.opsForZSet().removeRange(inboxKey, 0, -(MAX_INBOX_SIZE + 1));
```

**严重程度**：🟡 中（影响 Redis 内存，但有 7 天 TTL 兜底）

---

### 问题 4：FeedPushConsumer 中 Pipeline 未设置收件箱 TTL ⚠️

**位置**：`FeedPushConsumer.java` L93-110

**问题**：Pipeline 批量 ZADD 后没有为每个收件箱 Key 设置 EXPIRE。虽然 `FeedCleanupJob` 会定期清理过期数据，但如果用户取消关注后不再有新推送，该 Key 永远不会过期。

**修复建议**：在 Pipeline 中同时设置 EXPIRE，或在 ZADD 后批量设置：
```java
connection.keyCommands().expire(
    inboxKey.getBytes(StandardCharsets.UTF_8),
    inboxMaxDays * 24 * 3600L);
```

**严重程度**：🟢 低（有定时清理兜底，但存在内存泄漏风险）

---

### 问题 5：大V 判断缓存竞态条件

**位置**：`FeedPushConsumer.java` L135-150

**问题**：`checkBigV()` 方法先查缓存，缓存不存在时查 ZCARD 再写缓存。多实例并发消费同一作者的多条消息时，可能同时查 ZCARD 并写入缓存，但这不是严重问题（结果一致，只是多查了几次）。

**评价**：✅ 可接受。ZCARD 是 O(1) 操作，多查几次无性能影响。缓存 1 小时 TTL 合理。

---

## ✅ 三、技术亮点

### 亮点 1：推拉混合模型（面试核心考点）

```
普通用户发笔记 → 推模式（写扩散）→ 遍历粉丝，ZADD 到每个粉丝收件箱
大V发笔记 → 拉模式（读扩散）→ ZADD 到自己的发件箱，粉丝读取时实时拉取
读取 Feed → 收件箱 UNION 大V发件箱 → 合并排序 → 游标分页
```

**为什么不全用推模式？** 大V 粉丝数百万，一条笔记推送百万次 ZADD，延迟不可接受。
**为什么不全用拉模式？** 用户关注 500 人，每次刷 Feed 要查 500 个发件箱，读放大严重。
**推拉混合的阈值？** 10 万粉丝为界，可配置（`big-v-threshold`）。

### 亮点 2：BFF 聚合层 + 2 层并行编排

```
第 1 层（并行）：笔记详情 + 点赞状态 + 未读数
第 2 层（依赖第 1 层的 authorIds）：作者信息 + 计数
总耗时 ≈ max(第1层) + max(第2层) ≈ 50ms + 30ms = 80ms
串行耗时 ≈ 5 × 50ms = 250ms
```

### 亮点 3：MDC TraceId 透传到线程池

自定义 `MdcAwareThreadFactory`，在任务提交时捕获父线程 MDC 上下文，子线程执行前恢复、执行后清理。保证分布式追踪链路不断。

### 亮点 4：游标分页（Search After 思想）

基于 Redis ZSet score（时间戳）实现游标分页，避免传统 OFFSET 分页的深翻页性能问题：
- OFFSET 分页：`ZREVRANGE key offset size` → O(offset + size)
- 游标分页：`ZREVRANGEBYSCORE key max min LIMIT 0 size` → O(log(N) + size)

### 亮点 5：Pipeline 批量写入 + MGET 批量查询

- 推模式：500 个粉丝一批 Pipeline ZADD，一次网络往返
- 大V 判断：MGET 批量查询所有关注用户的大V标记，避免 N+1

### 亮点 6：CallerRunsPolicy 降级策略

线程池队列满时，由调用线程（Tomcat 线程）执行任务，而非丢弃。保证请求不会因线程池满而失败，只是响应变慢。

---

## 🎤 四、面试话术

### Q1：Feed 流系统如何设计？推模式和拉模式的区别？

**A**：我们采用推拉混合模型。核心思路是根据用户的粉丝数动态选择策略：

- **推模式（写扩散）**：普通用户（粉丝 < 10 万）发笔记时，通过 MQ 异步遍历粉丝列表，Pipeline 批量 ZADD 到每个粉丝的 Redis ZSet 收件箱。优点是读取 Feed 时只需查自己的收件箱，O(log N) 复杂度；缺点是写放大。
- **拉模式（读扩散）**：大V（粉丝 ≥ 10 万）发笔记只写入自己的发件箱 ZSet。粉丝读取 Feed 时实时拉取关注的大V发件箱并合并。优点是写入 O(1)；缺点是读放大。
- **混合读取**：用户刷 Feed 时，从自己的收件箱 + 关注的大V发件箱分别拉取，多路归并排序，取 Top N。

阈值 10 万可配置，通过 Redis 缓存大V标记（1 小时 TTL），避免每次查询粉丝数。

### Q2：游标分页和传统分页的区别？为什么 Feed 流不能用 OFFSET？

**A**：传统 OFFSET 分页在深翻页时性能急剧下降——`ZREVRANGE key 10000 10020` 需要跳过前 10000 条，时间复杂度 O(offset + size)。

游标分页基于 ZSet score（时间戳）：`ZREVRANGEBYSCORE key lastScore 0 LIMIT 0 size`，直接定位到上一页最后一条的 score 位置，时间复杂度 O(log N + size)，与翻页深度无关。

客户端每次请求携带上一页返回的 `nextCursor`（最小 score），服务端以此为起点向前查询。

### Q3：BFF 聚合层如何保证性能？下游服务超时怎么办？

**A**：三个关键设计：

1. **2 层并行编排**：第 1 层并行获取笔记详情 + 点赞状态 + 未读数；第 2 层依赖第 1 层的 authorIds 并行获取作者信息 + 计数。总耗时 ≈ max(两层) 而非 sum。
2. **超时降级**：每层设置总超时（3s / 2s），超时后 `CompletableFuture.getNow(默认值)` 返回空数据，前端展示"加载中"占位。
3. **独立线程池**：不用 ForkJoinPool.commonPool()，自定义 IO 密集型线程池（core=20, max=50），队列满时 CallerRunsPolicy 降级。

### Q4：Feed 流的收件箱如何防止无限膨胀？

**A**：三层防护：
1. **TTL**：收件箱 ZSet 设置 7 天过期
2. **定时清理**：每天凌晨 3 点 SCAN 遍历所有收件箱，ZREMRANGEBYSCORE 删除 7 天前的数据
3. **分布式锁**：清理任务用 Redisson 分布式锁保证多实例只执行一次

---

## 📁 五、文件清单

| 文件 | 行数 | 职责 |
|------|:----:|------|
| FeedService.java | 415 | Feed 流核心：推拉混合读取 + 并行聚合 + 游标分页 |
| FeedPushConsumer.java | 157 | MQ 消费者：笔记发布事件 → 推/拉模式写入收件箱/发件箱 |
| FeedCleanupJob.java | 102 | 定时任务：清理过期收件箱数据（分布式锁保护） |
| HomeController.java | 75 | REST 接口：Feed 流查询 + 测试接口 |
| AggregatorThreadPoolConfig.java | 89 | 线程池配置：MDC 透传 + CallerRunsPolicy |
| HomeApplication.java | 35 | 启动类：排除 DataSource 相关组件 |
| FeedVO.java | 31 | 响应 DTO：笔记列表 + 游标 + 未读数 |
| NoteCardVO.java | 45 | 笔记卡片 DTO：聚合后的完整信息 |
| AnalyticsFeignClient.java | 59 | Feign：社交服务（点赞/关注） |
| application.yml | 109 | 配置：线程池参数 + 下游地址 + Feed 参数 |

---

## 🏆 六、总结

**14-Feed 流首页**是整个项目中面试价值最高的模块之一，涵盖了：
- 推拉混合模型（写扩散 vs 读扩散的权衡）
- 游标分页（替代 OFFSET 的深翻页方案）
- BFF 并行聚合（CompletableFuture 编排 + 超时降级）
- Redis Pipeline 批量操作
- 分布式定时任务（Redisson 锁）

主要改进方向：
1. 解决嵌套 CompletableFuture 的线程池饥饿风险
2. 收件箱增加容量上限保护
3. 增加 Metrics 埋点（聚合耗时、降级次数、收件箱大小）
