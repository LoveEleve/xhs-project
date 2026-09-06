# my-xhs-notification 模块分析

## 1. 模块定位
通知域（19013）：消费 NOTIFICATION_TOPIC 行为事件（点赞/评论/关注等），聚合/渲染后经 SSE 实时推送 + 未读数管理。含 NotificationController、NotificationEventConsumer、NotificationService、NotificationAggregator、SSE（SseEmitterManager + SseCrossInstanceSubscriber）、UnreadCountService + UnreadReconcileJob（XXL-Job）。

## 2. 代码事实
- 19 个 java：1 Consumer（NOTIFICATION_TOPIC）、2 Controller（普通+测试）、4 Service（Aggregator/SseTicket/Unread/Notification）、SSE 3、Job 1、Mapper 2

## 3. 核心链路
```text
行为事件(NOTIFICATION_TOPIC) → NotificationEventConsumer
→ NotificationService: 持久化 t_notification + 聚合(NOTIFICATION聚合窗口)
→ SseEmitterManager: 推送给在线用户(SSE长连接)
→ UnreadCountService: 未读数 Redis INCR
SSE: NotificationController /sse(ticket鉴权) → SseEmitter 保持连接，跨实例用 SseCrossInstanceSubscriber
```

## 4. 数据流转
| 项 | 内容 |
|---|---|
| MySQL | t_notification、t_push_template（模板渲染 title/content） |
| Redis | 未读数 key、SSE ticket（ticket 鉴权）、在线状态 |
| MQ | 消费 NOTIFICATION_TOPIC（通知事件） |
| SSE | 跨实例订阅推送（Redis pub/sub 或类似），保证多实例通知可达 |

## 5. 关键决策
- SSE 需 ticket 鉴权（EventSource 无法带 Authorization 头）
- 推送模板（t_push_template）渲染：title_template/content_template 用 {} 占位
- 聚合通知（同事件聚合）减少推送量
- 未读数对账：UnreadReconcileJob（XXL-Job 每 10 分钟）

## 6. 运行态验证
- **未触发真实通知链路**（未发 NOTIFICATION_TOPIC 测试消息）
- 中间件就绪：NOTIFICATION_TOPIC 已创建、consumer group 在线（DLQ 监控可见）、SSE 端点白名单确认
- 待后续真实行为事件触发验证

## 7. 鉴权基础
- /api/notification/sse 走 ticket 鉴权（白名单，EventSource 无 header）
- 其他接口需 JWT

## 8. 风险
- SSE 长连接保活/超时（真实 timeout 待验证）
- 聚合窗口与实时性权衡
- 未读数 Redis 与 MySQL 一致性靠对账 Job

## 9. 覆盖对账
- Consumer/SSE 机制/Service 结构已读（核心链路）；NotificationAggregator 聚合细节标注
- Mapper/DTO 简单类未逐行
- **运行态未全面实测**（通知链路待行为事件触发）
