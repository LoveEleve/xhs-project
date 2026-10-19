---
iteration: ITER-012
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 技术方案: 通知模块生产化改造

## 方案概述

```
可靠性收敛（核心）：
  F2 DLQ 重放 ×2：
    NotificationDlqReplayConsumer（两监听器）：
      消费 %DLQ%notification-event-consumer-group / %DLQ%notification-order-termination-consumer-group
      重投源 topic（NOTIFICATION_TOPIC / ORDER_TERMINATION_TOPIC），保留：
        - KEYS 属性（order-termination 的 orderNo 幂等键——跨重投稳定）
        - 原 msgId 属性（originalMsgId——notification-event 的 msgId 幂等键）
        - dlqReplayCount（attempt≤3 防循环；失败不吞）
  消费者幂等键升级（NotificationEventConsumer）：
    key = msg.getProperty("originalMsgId") != null ? 原值 : msg.getMsgId()
    → 重放"已处理"消息被 msgId 去重命中（安全）；未处理消息正常重跑
  F3 重试幂等：
    processEvent 的 post-commit 步骤（incrementUnread / getUnreadCount）包 try/catch：
      失败 → log warn（降级）+ 不抛 → 消息 ACK（不触发重试）→ 未读由 10min 对账收敛
    聚合步骤（processWithAggregate）异常照旧抛出（事务回滚干净 → 重试安全）
  F4 窗口锁自愈（NotificationAggregator）：
    ① INSERT 异常 → catch 中 best-effort DELETE 窗口键（重试重选 → 新窗口）
    ② PENDING 超时回退 → DELETE 键 + 独立插入（后续事件重选）
    ③ increment affected ≤ 0（键指向已回滚/已删行）→ DELETE 键 + 独立插入

订单状态通知 outbox 化（F6，order 侧 bounded）：
  OrderNotificationPublisher.publishStatusChanged 改为：
    构建事件 JSON → 事务内落 t_order_local_message（operationType=ORDER_STATUS_NOTIFICATION）
    → 提交后发送（失败留库）→ LocalMessageRetryJob.resolveTopic 新增映射 → NOTIFICATION_TOPIC 补发
  语义：与 04B 终结通知同构（即时发送 + 补发兜底）；落库失败不影响交易主链路

工程对齐：
  F1 注释回正（init-all ×2：1-笔记 2-商品 3-订单）
  F5 限流（list/unread-count/sse-ticket）+ 文档修正
  §7 死物（updateAggregateInfo / isAggregated / aggregateId 删除；死表 DDL 注释标注）
  §7 javadoc 修正（5 分钟→自然日；mapper 返回值漂移）；forceSetUnread TTL；脚本静态
```

三阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 可靠性收敛 | REQ-1 DLQ ×2 + REQ-2 重试幂等 + REQ-3 窗口自愈 + REQ-4 outbox | 兜底闭环 + 双计消除 |
| ② 工程对齐 | REQ-5 限流/文档 + REQ-6 注释回正/死物/TTL/脚本 | 水准对齐 |
| ③ 收口 | REQ-7 测试集 + 回归 + 回写 | 闭环 |

## REQ → 任务映射

### 阶段① 可靠性收敛（REQ-1/2/3/4）

| 任务 | 文件 | 要点 |
|------|------|------|
| T1.1 DLQ 重放 ×2 | 新 `consumer/NotificationDlqReplayConsumer.java` | 镜像 C-011 SearchDlqReplayConsumer；保留 KEYS + originalMsgId + dlqReplayCount |
| T1.2 幂等键升级 | `NotificationEventConsumer` | originalMsgId 属性优先；order-termination 已有 KEYS 去重（补原 msgId 属性一致化） |
| T1.3 重试幂等 | `NotificationService.processEvent` | post-commit 步骤 try/catch 降级（含 getUnreadCount）；javadoc 写清"红点滞后 ≤10min 由对账收敛" |
| T1.4 窗口自愈 | `NotificationAggregator` | 三路径 DELETE 键（best-effort，Redis 异常仅告警） |
| T1.5 outbox 化 | `OrderNotificationPublisher` + `LocalMessageRetryJob` + 常量 | operation 类型 ORDER_STATUS_NOTIFICATION；落库 + 提交后发送；映射补发 |

### 阶段② 工程对齐（REQ-5/6）

| 任务 | 文件 | 要点 |
|------|------|------|
| T2.1 限流 | `NotificationController` | list 30/min、unread-count 60/min、sse/ticket 10/min（perUser）；/sse 不加（票据一次性+50k 帽，注释写明） |
| T2.2 文档修正 | `09-通知.md` §5 | "均已限流"→ 准确表述（read×3 + list/unread-count/ticket；/sse 票据门禁） |
| T2.3 F1 回正 | `sql/init-all.sql` + `deploy/.../sql/init-all.sql` | target_type 注释 = 1-笔记 2-商品 3-订单（对齐 DTO/生产方/另两副本；去掉"C-009 修正注释"标记） |
| T2.4 死物 | `NotificationMapper`（删死方法）+ `Notification`（删死字段）+ DDL 注释 | 死表 t_push_task/t_push_task_fail 标注 DEPRECATED（不删表——迁移链零收益） |
| T2.5 杂项 | `UnreadCountService`（TTL + 静态脚本）+ `NotificationService`/`NotificationMapper` javadoc | forceSetUnread 双键 30d TTL；resetUnreadByType 静态脚本；"5 分钟"→自然日；mapper 返回值口径 |

### 阶段③ 收口（REQ-7）

测试集（从零）→ 十二模块回归 → §8 回写（09-通知）+ 面试材料 → change done。

## 关键决策与理由

1. **F3 吞 vs 重试**：post-commit 失败吞掉+对账兜底——重试会重跑聚合（双计），而未读有 10min 对账收敛（DB 权威）→ 吞更收敛；
2. **重放保留原 msgId**：notification-event 的幂等键就是 msgId（重放换新 msgId 会绕过去重）→ 保留属性使重放安全（家族首个"消息级幂等"重放场景）；
3. **F6 修而非讲**：交易类通知与社交通知分级——社交=best-effort（口径已有）、终结=Outbox、状态通知原为夹层（asyncSend）→ 收敛到 Outbox（04B 基建复用，成本 bounded）；
4. **窗口删键而非回填**：删键让下一事件重选（自愈最快）；回填需知道"谁的 id 有效"，PENDING 场景不可判定；
5. **死表不删**：DDL 删除有迁移链成本，注释标注 + 零引用即可（与 C-009 死代码删除的边界区分：代码删、表留注）。
