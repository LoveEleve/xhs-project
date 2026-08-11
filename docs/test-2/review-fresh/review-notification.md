# Notification 模块 Review

## 业务逻辑
1. **[低] 聚合窗口 TTL 与文档不符** (NotificationAggregator.java:48-54)
   类注释称"5 分钟窗口"，但 `getAggregateWindow()` 实为"当天剩余秒数"（对齐按天唯一索引）。
   实际聚合窗口是到当天结束，可能长时间过度聚合同一用户/类型/目标的多次通知为一条。
   需确认是否业务预期（若希望 5min 窗口则与实现不符）。

2. **[低] 模板占位符替换语义错乱** (NotificationService.java:172-176)
   title 里 `{title}` 和 `{content}` 都被替换为 targetName，标题/内容占位符不区分。
   模板渲染结果可能不符合预期。

## 一致性 / 分布式
3. **[中] 未读计数增量与 DB 插入非原子** (NotificationService.java:62-69)
   processEvent 中 DB 新建通知 + Redis INCR 未读 分步执行；标记已读时 DB update + DECR 亦分步。
   极端下未读数会漂移（依赖 UnreadReconcileJob 兜底）。可接受但需知悉。

4. **幂等完备**：MQ msgId 去重 + 失败 removeMark 重试 + 聚合窗口 SETNX Lua 原子 —— 好。
5. **聚合 Lua 原子 + PENDING 自旋** —— 并发安全，设计成熟。
6. SSE 跨实例经 Redis PubSub（SseCrossInstanceSubscriber）—— 需确认，结构合理。

## 工程
7. 模块规模小、职责清晰。整体质量中等偏上，主要问题集中在模板渲染语义与聚合窗口文档不一致。
