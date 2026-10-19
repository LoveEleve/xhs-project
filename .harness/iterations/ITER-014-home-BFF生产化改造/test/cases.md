---
iteration: ITER-014
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试用例: home BFF 生产化改造

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | — | FeedService 请求 | 内层 5 future 提交 batchFeignPool（execute 断言） | 是 |
| TC-2 | AC-1 | — | UserProfile 请求 | 内层 6 future 提交 batchFeignPool | 是 |
| TC-3 | AC-1 | — | Product/Cart 请求 | L1 future 提交 batchFeignPool | 是 |
| TC-4 | AC-2 | id 为 String | likeCollect | 正确提取（不再恒 0） | 是 |
| TC-5 | AC-2 | 250 篇笔记 | likeCollect | 分批 ≤100/批（captor），3 批累计 | 是 |
| TC-6 | AC-2 | 批 2 请求失败 | likeCollect | 累计批 1+3（部分成功，不归零） | 是 |
| TC-7 | AC-2 | >500 篇 | likeCollect | 页数 ≤10 上限 | 是 |
| TC-8 | AC-3 | notesFuture 挂起（超时） | FeedService | DownstreamUnavailable（503） | 是 |
| TC-9 | AC-3 | cartFuture 挂起（超时） | CartAgg | DownstreamUnavailable（503） | 是 |
| TC-10 | AC-4 | DLQ（feed 组） | 重放 | FEED_TOPIC + attempt+1 | 是 |
| TC-11 | AC-4 | DLQ（note-delete 组） | 重放 | SOCIAL_TOPIC:NOTE_DELETE（tag 保留） | 是 |
| TC-12 | AC-4 | attempt=3 / 发送失败 | 重放 | 超限不投+指标 / 抛出不吞 | 是 |
| TC-13 | AC-5 | 空 feed | FeedService | unreadCount = notification 真实值（非 0 写死） | 是 |
| TC-14 | AC-5 | 无大V关注 | FeedService | sentinel "-1" 写缓存 + 读回过滤（不重扫） | 是 |
| TC-15 | AC-5 | fans=100000 整（缓存陈旧 0） | FeedPushConsumer | `>=` 保护生效 + outbox + markPushCompleted | 是 |
| TC-16 | AC-5 | 已删标记命中 | FeedPushConsumer | 跳过 + markPushCompleted（不重投） | 是 |
| TC-17 | AC-6 | — | FeedService 卡片 | createdAt/score 填充 | 是 |
| TC-18 | AC-6 | — | grep | 注释零漂移（4 处）+ NOTE_DELETED_MARK 常量 | 是 |
| TC-19 | AC-6 | — | HomeController | @RateLimit ×5（反射） | 是 |
| TC-20 | AC-7 | 全仓 | 十四模块重编测试 | 全绿 | 是 |
| TC-21~40 | 各 AC | 既有路径回归 | 合并去重（同 noteId 保高分）/游标开区间/hasMore/脏成员跳过/大V拉取 perBigV/进度断点/降级分档（关键 503·辅助空值）等 | 与现语义一致 | 是 |
