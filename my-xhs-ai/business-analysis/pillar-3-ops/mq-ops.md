# RocketMQ 排障（积压 / DLQ / outbox / 事务消息）

> my-xhs 的**异步一致性骨架**：事务消息、Outbox、本地消息表、补偿都绕不开 MQ。

## 业务问题（AI 能回答）
- **消费积压**：consumer offset 滞后、某 topic 堆积。
- **死信 DLQ**：`%DLQ%{group}` 消息、失败原因。
- **Outbox/本地消息表积压**：补发 Job 是否跟上。
- **事务消息失败**：半消息回查、下单不可用。
- **重复消费**：幂等去重是否生效。

## 可用的数据资产与就绪度
| 排障目标 | 数据来源 | 就绪度 |
|---------|---------|:---:|
| 消费位点/积压 | Prometheus `rocketmq_consumer_offset` | ✅ |
| 死信 | RocketMQ Dashboard(18081) / 消费日志 | ⚠️ **无 DLQ 消费者** |
| Outbox 积压 | t_coupon_outbox / t_inventory_outbox / t_local_message | ✅ |
| 事务消息 | t_local_message(status, retry_count) | ✅ |
| 重复消费 | Redis 幂等键 + 日志 | ⚠️ 需观测 |

> **已知缺口**：无 DLQ 消费者，死信消息需运维手动从 Dashboard 重投或新建消费者（`failover-scenarios.md` §四）。

## 关键诊断点
1. **Broker DOWN**：下单不可用（事务消息失败）；Outbox 类有兜底（5s 补发 Job），Feed/购物车靠重试 Job。
2. **消费积压**：恢复后批量追赶，maxReconsumeTimes 超限进 DLQ。
3. **幂等标记时机**：消费者必须在业务成功后写幂等标记，失败 removeMark 再 throw（防重试窗口归零）——项目核心规范。
4. **补偿 Job**：LocalMessageRetryJob(30s)、CouponOutboxSenderJob(5s)、InventoryOutboxSenderJob(5s)。

## 关联
- 消息积压/死信是**业务数字异常与功能中断**的常见根因 → 关联 ops-health + 各业务支柱。
- 中间件故障恢复见 `failover-scenarios.md` §四。
