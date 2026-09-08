# my-xhs-notification 模块深度分析

## 1. 当前模块定位

notification（19013）是通知服务：消费 `NOTIFICATION_TOPIC` 行为事件（点赞/评论/关注），模板渲染标题，Redis 窗口聚合同类通知，落库 t_notification，更新未读数，SSE 实时推送（在线用户），并提供通知列表/已读/未读接口。UnreadReconcileJob（XXL-Job）对账未读数。

## 2. 当前代码事实

- 启动入口 `NotificationApplication`；Controller：NotificationController、NotificationTestController。
- 消费者：NotificationEventConsumer（NOTIFICATION_TOPIC，MessageIdempotentHelper 幂等）。
- Service：NotificationService（processEvent/列表/已读/未读）、NotificationAggregator、UnreadCountService、SseTicketService。
- SSE：SseEmitterManager、SseCrossInstanceSubscriber。
- Job：UnreadReconcileJob。
- Mapper：NotificationMapper、PushTemplateMapper；实体 Notification/PushTemplate。

## 3. 关键业务链路与源码流转

### 3.1 通知处理

```text
NOTIFICATION_TOPIC 事件 → NotificationEventConsumer
  - msgId 幂等（24h） + 自通知跳过
→ NotificationService.processEvent
  - buildNotification（PushTemplate 渲染 title/content）
  - aggregator.processWithAggregate（Redis 窗口聚合，同类合并）
  - 新建则 unreadCountService.incrementUnread
  - SSE 在线则 pushNotification + pushUnreadCount
→ t_notification 落库
```

### 3.2 聚合

```text
aggregateKey = 窗口 key（Redis SETNX 5min）
首次事件: 建立主通知
窗口内后续同类: 更新 aggregate_count + title（"xxx 等 N 人"）
```

### 3.3 未读数

```text
increment/decrement/resetUnread[ByType]: Redis 计数
getUnreadCount: 汇总 total + type 明细
UnreadReconcileJob: 对账 Redis 与 DB 未读数
```

### 3.4 SSE

```text
GET /api/notification/sse（ticket 鉴权，白名单）→ SseEmitterManager 注册
跨实例: SseCrossInstanceSubscriber 订阅，保证多实例通知可达
SseTicketService: 签发/校验 SSE ticket
```

## 4. 数据流转

| 中间件 | key/表/topic | 说明 |
|---|---|---|
| MySQL | t_notification、t_push_template | 通知/模板 |
| Redis | 未读数 key、SSE ticket、聚合窗口 key | 未读/在线/ticket/聚合 |
| MQ | NOTIFICATION_TOPIC | 通知事件（消费） |
| SSE | SseEmitterManager | 实时推送（在线） |

## 5. 跨模块与分布式行为

- 消费 analytics/content 的行为事件（点赞/评论/关注）。
- SSE 跨实例订阅保证多实例通知可达。
- msgId 幂等 + 聚合窗口锁双保险。

## 6. 性能与工程质量

- 聚合减少通知量；SSE 长连接复用。
- 未读对账 Job 兜底。
- 模板渲染占位替换。

## 7. 鉴权基础检查

- SSE 走 ticket 鉴权（EventSource 无 header）；其他需 JWT。

## 8. 当前分支/改动点

- msgId 幂等、聚合窗口、SSE 跨实例、未读对账。

## 9. 风险与测试重点

- 代码：聚合窗口一致性、未读并发增减。
- 业务：通知类型模板、聚合合并、自通知跳过。
- 分布式：SSE 跨实例、消息幂等。
- 可观测：通知失败、聚合、SSE 连接指标。

## 10. 覆盖对账

Consumer/Service/Aggregator/Unread/SSE 已深读；Mapper/实体/DTO 随调用链核对。
