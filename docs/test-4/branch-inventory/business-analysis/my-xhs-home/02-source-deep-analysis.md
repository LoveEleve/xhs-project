# my-xhs-home 模块深度分析

## 1. 当前模块定位

home（19015）是首页聚合服务：将多个下游服务（user/content/product/cart/counter/coupon/analytics/notification/inventory）的数据异步并行聚合为 Feed、笔记详情、商品详情、用户主页、购物车聚合视图。同时消费 `FEED_TOPIC` 做粉丝收件箱/大V发件箱推送，消费 `SOCIAL_TOPIC` 处理笔记删除清理。

## 2. 当前代码事实

- 启动入口 `HomeApplication`；Controller：HomeController（feed/note/product/user/cart 聚合）、FeedTestController。
- 聚合 Service：FeedService、NoteAggService、ProductAggService、UserProfileAggService、CartAggService。
- 消费者：FeedPushConsumer、NoteDeleteConsumer。
- Job：FeedCleanupJob（XXL-Job）。
- Feign 客户端 8 个 + 各自 FallbackFactory；AggregatorThreadPoolConfig（自定义异步线程池）。

## 3. 关键业务链路与源码流转

### 3.1 聚合

```text
GET /api/home/{feed|note|product|user|cart}
→ HomeController CompletableFuture.supplyAsync（释放 Tomcat 线程）
→ AggService：并行 CompletableFuture 编排多 Feign 调用
→ 各 Feign 失败走 FallbackFactory（降级为空/默认值，不拖垮聚合）
→ 组装聚合 VO 返回
```

### 3.2 Feed 推送

```text
发布笔记 → FEED_TOPIC(NotePublishEvent)
→ FeedPushConsumer
  - 大V（粉丝数>=阈值）: 写作者发件箱（Redis ZSet，拉模式）
  - 普通用户: 批量写粉丝收件箱（推模式，push progress 记录断点）
→ NoteDeleteConsumer(SOCIAL_TOPIC NOTE_DELETE): 清理已删笔记的收件箱/发件箱
```

## 4. 数据流转

| 中间件 | key | 说明 |
|---|---|---|
| Redis | `myxhs:feed:outbox/inbox`、push progress | Feed 收件箱/发件箱/推送进度 |
| Redis | `myxhs:note:deleted:{noteId}` | 已删标记（5min，防乱序） |
| MQ | FEED_TOPIC（NotePublishEvent）、SOCIAL_TOPIC（NOTE_DELETE） | 推送/清理 |
| Feign | 8 个下游服务 + Fallback | 聚合数据源 |

## 5. 跨模块与分布式行为

- 聚合用 CompletableFuture 并行，Spring MVC 异步上下文释放线程。
- 各 Feign 有降级工厂，单服务故障不拖垮首页。
- Feed 推送进度 Redis 记录，失败断点续推；大V/普通用户不同模式。

## 6. 性能与工程质量

- 异步线程池 AggregatorThreadPoolConfig 隔离。
- Feed 推送批量 + 进度断点。
- 收件箱有最大条数/天数限制（inbox-max-days/size）。

## 7. 鉴权基础检查

- 聚合接口需 JWT（X-User-Id）；部分公开读（笔记/商品详情）在白名单。

## 8. 当前分支/改动点

- FeedPushConsumer 字符串 ID 兼容修复（noteId/authorId/publishTime toLong）。
- 已删标记防乱序（T-126）。

## 9. 风险与测试重点

- 代码：Feign 降级返回语义、聚合 VO 组装。
- 业务：大V/普通推送模式、已删笔记乱序。
- 性能：并行聚合响应时间、收件箱膨胀。
- 可观测：推送进度、降级触发指标。

## 10. 覆盖对账

HomeController/FeedPushConsumer/NoteDeleteConsumer/Agg Service 核心已深读；Feign 客户端+Fallback 结构确认；DTO 随调用链核对。
