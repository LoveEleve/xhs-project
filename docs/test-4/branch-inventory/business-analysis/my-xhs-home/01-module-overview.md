# my-xhs-home 模块分析

## 1. 模块定位
首页 Feed 聚合层（19015）：聚合多服务数据（关注 Feed、笔记详情、商品详情、用户主页、购物车聚合）。大量 Feign 并行调用（analytics/content/counter/coupon/cart）+ FeignFallbackFactory 降级。同时消费 FEED_TOPIC 做粉丝收件箱/大V发件箱推送。

## 2. 代码事实
- 38 个 java：HomeController（5 聚合接口）、FeedPushConsumer/NoteDeleteConsumer、5 Feign + 各自 FallbackFactory、DTO
- CompletableFuture 并行聚合（自定义线程池 AggregatorThreadPoolConfig）

## 3. 核心链路
```text
GET /api/home/feed → 并行Feign(关注列表+内容+计数) → FeedVO 聚合返回
GET /api/home/note/{id} → 并行Feign(笔记详情+作者+点赞收藏计数) → 聚合
发布笔记(FEED_TOPIC) → FeedPushConsumer:
   大V→发件箱(Redis ZSet) / 普通→粉丝收件箱批量推送
   NOTE_DELETE → NoteDeleteConsumer 清理收件箱/发件箱
```

## 4. 数据流转
| 项 | 内容 |
|---|---|
| Redis | feed outbox/inbox（ZSet）、push progress、note deleted 标记 |
| MQ | 消费 FEED_TOPIC（NotePublishEvent）、SOCIAL_TOPIC（NOTE_DELETE） |
| 跨模块 | Feign：analytics(content 关注)、content、counter、cart、coupon；全部有 FallbackFactory 降级 |

## 5. 关键决策
- 大V 判断：粉丝数 >= 阈值 → 拉模式（发件箱）；普通 → 推模式（粉丝收件箱批量）
- 推送进度 Redis 记录，失败断点续推
- Feign 聚合全部 CompletableFuture + 降级（单服务故障不拖垮首页）

## 6. 运行态验证
- Feed 推送实测通过：发布笔记 → FEED_TOPIC → FeedPushConsumer 推模式完成（noteId/localMsgId 正确）
- **FeedPushConsumer 字符串 id 兼容已修复**（noteId/authorId/publishTime 裸 (Number) 强转 → toLong 兼容）

## 7. 鉴权基础
- 聚合接口需 JWT（X-User-Id）；部分公开读（笔记详情）在白名单

## 8. 风险
- 大量 Feign 串/并行聚合的响应时间（有超时配置 + 降级）
- 收件箱 Redis 膨胀（inbox-max-days 7d / max-size 500 限制）

## 9. 覆盖对账
- HomeController/FeedPushConsumer/NoteDeleteConsumer 已读；Feign 客户端+降级工厂机制已确认（聚合调用链）
- DTO 聚合对象未逐行（简单）；NotePublishEvent 与 content 共用的消息契约已验证（body 字段）
