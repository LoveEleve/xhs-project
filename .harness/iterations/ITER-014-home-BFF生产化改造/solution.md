---
iteration: ITER-014
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 技术方案: home BFF 生产化改造

## 方案概述

```
可靠性收敛（核心）：
  F1 池隔离补全：
    FeedService：5 处 supplyAsync(..., aggregatorPool) → batchFeignPool（字段已注入）
    UserProfileAggService：6 处 → batchFeignPool（补注入字段）
    ProductAggService/CartAggService：L1 各 2 处 → batchFeignPool（L2 已迁）
    （对齐 NoteAggService 的 A3 全迁模式：外层 controller=aggregatorPool，内层=batchFeignPool）
  F2 likeCollect 修复（UserProfileAggService.likeCollectFuture）：
    id 提取：instanceof Number → toLongValue（String-aware，T-119 同族）
    分批：queries 按 ≤100/批 分片（CounterBatchRequest @Size(max=100)）
    部分成功：逐批累计（失败批次 log + 跳过，不归零）
    页数上限：page ≤ 10（≈500 篇近似统计，文档化；避开 counter batch-get 10/s 限流）
  F3 超时语义对齐：
    FeedService：L1 超时后 if (!notesFuture.isDone()) → DownstreamUnavailable（对齐 Product 模式）
    CartAggService：cartFuture 同（javadoc："不可用 → 503"，不再空车）
  F4 DLQ 重放 ×2（HomeDlqReplayConsumer，家族同构）：
    %DLQ%feed-push-consumer-group → FEED_TOPIC（保留 tag；重放幂等=ZADD）
    %DLQ%note-delete-consumer-group → SOCIAL_TOPIC:NOTE_DELETE（tag 保留必须——selector 路由）
    attempt≤3；失败不吞

Feed 语义与工程：
  G2 空路径 unreadCount：抽 fetchUnreadCount(userId) 私有方法（降级返 0），两处 early-return 与正常路径共用
  F5 bigV 空缓存：空结果写 sentinel "-1" + expire 5min；读路径过滤 "-1"
  F7 阈值：totalFollowers >= bigVThreshold（对齐 checkBigV）；保护路径补 markPushCompleted（四路径统一）
  G1 卡片：createdAt（note.get("createdAt") 解析）+ score（merged tuple score → noteId→score 映射）
  G3 缓存原子：SADD+EXPIRE 改 executePipelined（消除客户端崩溃窗口）
  G4/G6/F6/§7-1 注释与常量：
    FeedService line147"逐个调用"→"批量接口"；分页注释勘误（开区间保进度，代价=同分跨页剩余跳过，演进=复合游标）
    NoteDeleteConsumer：标记先行（清理前设置，拦截窗口最小化）；FeedPushConsumer："5min"→"7 天"；zCard 单调用
    RedisKeyConstants 新增 NOTE_DELETED_MARK；两处引用改常量
  §7-5 限流：feed 30/min、note/product/user 60/min、cart 30/min（perUser）
```

三阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 可靠性收敛 | REQ-1（池）+ REQ-2（统计）+ REQ-3（超时）+ REQ-4（DLQ） | 饥饿/恒 0/伪装消除 |
| ② 工程对齐 | REQ-5（Feed 语义）+ REQ-6（注释/常量/限流） | 水准对齐 |
| ③ 收口 | REQ-7 测试集 + 回归 + 回写 | 闭环 |

## REQ → 任务映射

### 阶段① 可靠性收敛

| 任务 | 文件 | 要点 |
|------|------|------|
| T1.1 池迁移 | FeedService/UserProfileAggService/ProductAggService/CartAggService | 内层 15 处 → batchFeignPool；UserProfile 补注入 |
| T1.2 likeCollect | UserProfileAggService | String id + 分批 ≤100 + 累计 + 页 ≤10 |
| T1.3 超时对齐 | FeedService/CartAggService | !isDone → DownstreamUnavailable |
| T1.4 DLQ ×2 | 新 `consumer/HomeDlqReplayConsumer.java` | 镜像 C-011/13 模式；tag 保留 |

### 阶段② 工程对齐

| 任务 | 文件 | 要点 |
|------|------|------|
| T2.1 Feed 语义 | FeedService/FeedPushConsumer | G2 未读抽取 / F5 sentinel / F7 >= + markPushCompleted / G1 createdAt+score / G3 pipeline |
| T2.2 注释与常量 | FeedService/NoteDeleteConsumer/FeedPushConsumer/RedisKeyConstants/FollowingRecallStrategy | F6 勘误 + G4/G6 + note:deleted 常量 + search 侧 javadoc |
| T2.3 限流 | HomeController | 5 端点 @RateLimit |

### 阶段③ 收口

测试集（从零，~8 类 ≥40 用例）→ 十四模块回归 → §8 回写 + 08-Feed推拉 面试材料同步 → change done。

## 关键决策与理由

1. **F1 全迁 batchFeignPool**：与 NoteAgg 的 A3 模式对齐（外层 aggregator、内层 batchFeign）；UserProfile 补注入；池容量（30/80/500）+CallerRuns 兜底——负载上升记录；
2. **F2 分批+累计**：100 是 counter 校验硬上限；页数 10（≈500 篇）避开 10/s 限流（20 页=10 请求贴限）；部分成功累计优于全零；
3. **F3 超时→503**：与 Product/Note/UserProfile 的 2026-09-20 修复同原则（不把"依赖超时"伪装成"没有数据"）；
4. **F4 重放 tag 保留**：note-delete 消费者按 selectorExpression 路由——丢 tag = 消息无效；
5. **F5 sentinel "-1"**：Redis 空 Set 自动删除，无法缓存空；sentinel 是标准做法；
6. **F6 保留开区间**：闭区间在"同分组大于页宽"时死循环（翻页进度无法保证）——开区间保进度、代价=罕见同分跨页剩余跳过（注释勘误 + 演进记录）。
