---
iteration: ITER-012
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试用例: 通知模块生产化改造

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | DLQ 消息（event 组） | 重放 | 重投 NOTIFICATION_TOPIC + originalMsgId 保留 + attempt+1 | 是 |
| TC-2 | AC-1 | DLQ 消息（termination 组） | 重放 | 重投 ORDER_TERMINATION_TOPIC + KEYS(orderNo) 保留 | 是 |
| TC-3 | AC-1 | attempt=3 | 重放 | 不重投 + over_limit 指标 | 是 |
| TC-4 | AC-1 | syncSend 抛异常 | 重放 | 抛出不吞 + fail 指标 | 是 |
| TC-5 | AC-2 | 消息带 originalMsgId（已处理过） | 消费 | 幂等命中跳过（不双计） | 是 |
| TC-6 | AC-2 | 正常消息 | 消费 | 用 msgId 去重 | 是 |
| TC-7 | AC-2 | incrementUnread 抛异常 | processEvent | 不抛出（吞 + warn）；聚合已提交不重跑 | 是 |
| TC-8 | AC-2 | getUnreadCount 抛异常（推送计数路径） | processEvent | 不抛出（SSE 计数跳过） | 是 |
| TC-9 | AC-3 | INSERT 抛异常 | processWithAggregate | 窗口键被 DELETE（重试重选） | 是 |
| TC-10 | AC-3 | PENDING 自旋 3 次仍 PENDING | processWithAggregate | 独立插入 + 键 DELETE | 是 |
| TC-11 | AC-3 | increment affected=0 | processWithAggregate | 独立插入 + 键 DELETE | 是 |
| TC-12 | AC-3 | 窗口首条 | processWithAggregate | INSERT + 回填真实 ID + count=1 | 是 |
| TC-13 | AC-3 | 窗口后续（真实 ID） | processWithAggregate | increment + 标题重建 + 返回主通知 | 是 |
| TC-14 | AC-3 | 订单通知聚合 | processWithAggregate | updateOrderLatest 刷新 content/extra | 是 |
| TC-15 | AC-4 | 状态变更 | publishStatusChanged | 落库（ORDER_STATUS_NOTIFICATION + 载荷）+ 发送 | 是 |
| TC-16 | AC-4 | 补发扫描 | LocalMessageRetryJob | ORDER_STATUS_NOTIFICATION → NOTIFICATION_TOPIC | 是 |
| TC-17 | AC-5 | — | 三端点 | @RateLimit 存在；/sse 无（注释口径） | 是 |
| TC-18 | AC-6 | — | forceSetUnread | total/type 双键 TTL=30d | 是 |
| TC-19 | AC-6 | — | grep | 死物零残留（方法/字段）；注释回正 ×2；静态脚本 | 是 |
| TC-20 | AC-7 | 全仓 | 十二模块重编测试 | 全绿 | 是 |
| TC-21~30 | 各 AC | 既有路径回归 | 未读 Lua（incr/decr/reset 防负）/对账（游标批/残留清零/日清）/SSE（连接/替换/清理/跨实例）/票据（一次性/过期） | 与现语义一致 | 是 |
