# my-xhs-notification 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| N-L1-01 | 通知事件消费落库 | NOTIFICATION_TOPIC 事件 | t_notification 落库 | ✅ |
| N-L1-02 | 模板渲染 | type=LIKE 模板 | title="点赞通知" | ✅ |
| N-L1-03 | 自通知跳过 | sender=target=10001 | ✅ 不落库 |
| N-L1-04 | 通知列表 | GET /api/notification/list | 分页+类型筛选 | ✅ |
| N-L1-05 | 标记单条已读 | POST /read/{id} | is_read=1 + 未读-1 | ✅ |
| N-L1-06 | 全部已读 | POST /read-all | ✅ 未读3→0 |
| N-L1-07 | 未读计数 | GET /api/notification/unread-count | total+type 明细 | ✅ |
| N-L1-08 | SSE ticket | POST /sse/ticket | 短期一次性 ticket | ✅ |
| N-L1-09 | SSE 长连接 | GET /sse?ticket= | 连接+心跳+通知+未读推送 | ✅ event:notification/未读 |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| N-L2-01 | 聚合窗口 | 同类事件合并 | ✅ 测试用户B等2人赞了 |
| N-L2-02 | aggregate_count/title | 同sender5条 | ✅ 等5人赞了 count=5 |
| N-L2-03 | 未读 Redis 结构 | 各 type 计数 | ✅ |
| N-L2-04 | MQ幂等 | MessageIdempotentHelper msgId去重 | ✅ |
| N-L2-05 | 对账修复 | ✅ unreadReconcileJob每10min执行200 |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| N-L3-01 | 重复事件幂等 | msgId去重 | ✅ MessageIdempotentHelper |
| N-L3-02 | 未读并发 | 5并发read-all | ✅ 未读2→0不超减 |
| N-L3-03 | SSE 跨实例 | 多实例在线 | 通知可达 | ⬜ |
| N-L3-04 | 已读幂等 | 重复已读 | 不重复减未读 | ✅ 已读归零 |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| N-L4-01 | 通知/聚合/未读日志 | ✅ 处理完成 |
| N-L4-02 | SSE 连接数指标 | ✅ Prometheus端点暴露 |
| N-L4-03 | TraceId跨MQ | 通知链路traceId透传 | ✅ MqTraceHelper |

## 已实测
- N-L1-01/02/04/05/07、N-L3-04 ✅（消费/模板/列表/已读/未读闭环）
- 聚合窗口/SSE 实时推送/未读并发需专项（SSE 客户端 + 高频事件）
