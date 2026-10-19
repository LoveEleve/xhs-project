---
id: C-014
slug: home-BFF生产化改造
status: confirmed
created: 2026-10-19
updated: 2026-10-19
iteration: ITER-014     # 机器可读迭代协议：.harness/iterations/ITER-014-home-BFF生产化改造/
relations:
  relates_to:
    - slug: docs/学习/模块对照/11-homeBFF
      reason: §7 六条线索收口 + 三轮深探 F1~F9/G1~G7（池隔离回旋镖/likeCollect 恒 0/超时语义）
    - slug: docs/学习/模块对照/00-执行方案
      reason: Phase 4 支撑域首站（home BFF；gateway 为 C-015）
  depends_on: []
---

# C-014 home BFF 生产化改造

> **立项（2026-10-19）**：Phase 4 支撑域首站。**三轮深探（38 文件全读 + 跨模块契约核验 + 线程池/降级链逐条推演）**：
> 核心 = **F1 线程池隔离回旋镖**（A3 修复不完整：Feed 的 batchFeignPool 注入未用 + UserProfile 无该池 + Product/Cart L1——饥饿场景同型） + **F2 likeAndCollectCount 恒 0**（T-119 修了 total 漏了 id + 超 100 上限）+ **F3 关键依赖超时语义不一致**（Feed 空流/Cart 空车）+ **F4 DLQ 缺口 ×2**；
> 另有 G1 卡片 createdAt/score 未填充、G2 空 Feed 未读恒 0、F5 空缓存不缓存、F7 阈值边界、G3 缓存非原子、F6 分页注释勘误、注释/常量/限流等。
> 规划详见 `.harness/iterations/ITER-014-home-BFF生产化改造/`。

## 用户故事

作为支撑域开发者，我想要把 home BFF 从"线程池饥饿风险 + 统计恒 0 + 超时伪装空数据 + 兜底缺口"升级到
"**池隔离补全 + 统计正确 + 降级语义自洽 + 全兜底**"，并建立测试集、收官支撑域首站。

## 非目标（Out of Scope）

- 给 BFF 建库（无库是正确姿势，讲）；Feed 真推拉混合重设计；卡片半成品字段补全（isCollected/onShare 等——讲）；
- 索引分页在粉丝集合变更时的跳过（演进=值游标，记录）；复合游标（score+member）演进（记录）。

## 契约影响

- REST: 5 端点补 @RateLimit；FeedVO 空路径 unreadCount 语义修正（0 → 真实值）；NoteCardVO createdAt/score 开始填充
- MQ: 新增 DLQ 重放 ×2 组（feed-push / note-delete）
- Redis: `myxhs:note:deleted:` 提常量（无键变更）；bigV 缓存空结果 sentinel（"-1"）
- 数据模型: 无

## 影响面

- 模块/服务: my-xhs-home（主体）；my-xhs-common（常量）；my-xhs-search（FollowingRecall javadoc 勘误）
- wiki: 无（归档时回流）

## 测试策略

- **测试集从零建立**（模块当前零测试）：Feed 组装/池隔离断言/降级语义/消费者（含 DLQ）/聚合服务（likeCollect 分批）/控制器
- 回归：**十四模块**（home 首次纳入回归集）
