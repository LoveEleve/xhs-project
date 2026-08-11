# Home 模块 Review

## Feed 流
1. **[高] 收件箱读取参数颠倒 → 普通用户推模式 Feed 恒为空** (FeedService.java:81-83)
   `reverseRangeByScoreWithScores(inboxKey, minScore, 0, 0, size)`：范围 [minScore, 0]，
   而 score 是正数毫秒时间戳（~1.7e12），max=0 使区间为空 → 收件箱恒返回空。
   应改为 `(inboxKey, 0, lastScore, 0, size)`（min=0, max=lastScore）或用 maxScore 上界。
   **后果**：普通用户（非大V）的 Feed 只依赖大V发件箱拉取，推模式笔记完全不显示。
   （与已知 REVIEW 记录"H01-feed 参数颠倒"一致，代码确认。）

2. **[中·性能] Feed 聚合下游放大严重** (FeedService.java:374-454)
   每页 feed：逐条并行调 content 笔记详情(20) + 逐条并行调 user 用户信息(N) + counter 批量 + analytics 批量。
   单次 feed 请求 → 20+ 次下游 HTTP，QPS 放大明显；content/user 未提供批量接口。
   + Feed 本身无缓存 → 高并发下下游压力大。建议提供批量详情接口 + 缓存。

## 工程
3. **MDC 透传到位**：aggregatorPool/batchFeignPool 用 MdcAwareExecutorService（AggregatorThreadPoolConfig.java:116-163）
   每次 execute 捕获/恢复 MDC —— 全仓最佳实践，可作为 O2 修复的参照模板。
4. 双层线程池隔离（外层编排/内层批量 Feign）避免饥饿 —— 设计成熟。
5. mergeAndSort 去重、游标精度、超时降级（3s/2s + getNow 兜底）—— 合理。

## 聚合服务
6. CartAgg/ProductAgg/UserProfileAgg/NoteAgg —— BFF 聚合，各带 Feign fallback factory，降级思路正确。
