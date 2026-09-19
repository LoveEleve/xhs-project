# DLQ 三级治理：运行时证据 + 四个监控缺陷修复（2026-09-19）

> 目的：为 xhs/15（DLQ 治理）补运行时证据；本次深挖发现并修复 4 个监控缺陷，并做受控毒丸演练验证全链路。

## 一、机制全貌

```
① MQ 自动重试（maxReconsumeTimes=3）→ ② 进 %DLQ%<group> + Prometheus 指标 + P1 告警 → ③ 归档/重投（DLQ 报告留档 + AI HITL 工具）
监控实现：DlqMetrics 每 30s 拉取各 %DLQ% topic 的 min/max offset → gauge(rocketmq_dlq_backlog)；
         新增死信增量 → counter(myxhs_mq_dlq_total) → DlqMessageDetected 告警
```

## 二、运行证据（修复前）

| 证据 | 结果 |
|---|---|
| 历史死信实体 | `%DLQ%cart-event-sink-group` 11 条、`cart-sync-consumer-group` 3 条**仍在 broker**（printMsgByQueue 可读，offset 0–10，最后写入 09-15） |
| 09-17 清理报告 | 只归档未删除（`dlq-cleanup-20260917.md` 的 14 条与 broker 现存完全一致） |
| 告警现状 | `RocketmqDlqBacklog` 持续 firing 8+ 小时（15:xx 起）；同一组出现 **15× 重复**（每个服务都注册全平台 26 组的 gauge） |
| 死指标 | `myxhs_mq_dlq_total` 恒 0（`DlqMessageHandler` 无任何调用方）→ `DlqMessageDetected` 规则永不触发 |

## 三、受控毒丸演练（修复过程中的实测）

- 发送 `POISON-not-json` → NOTIFICATION_TOPIC → 消费端 4 次失败（首投+3 重试）→ 19:19:12 进入 `%DLQ%notification-event-consumer-group`（topicStatus max=1）。
- gauge `rocketmq_dlq_backlog{notification-event-consumer-group}` 由 -1 → **1 → 2**（第二枚毒丸）。
- `RocketmqDlqBacklog` 告警 **pending → firing**（"积压 2 条，持续 5 分钟"）✓。
- counter 增量路径：poller 记录 `myxhs_mq_dlq_total{consumerGroup=..., topic=...}=1` + 日志"检测到新死信: 新增=1条" ✓。
- **暴露缺陷**：counter 序列首次出现即=1（无 0 基线）→ Prometheus `increase()` 无法计算首次增量 → 首条死信 `DlqMessageDetected` 漏报。

## 四、修复内容

| # | 问题 | 修复 |
|---|---|---|
| 1 | 硬编码全平台 26 组：新增消费者漏改即盲区；每服务 26 个 PullConsumer（≈390），同一组被 15 份冗余监控 | `DlqMetrics` 启动时从 `@RocketMQMessageListener` **动态发现本服务消费组**（notification 实测只监控 1 组） |
| 2 | `myxhs_mq_dlq_total` 死指标 | poller 检测 backlog 增量 → `recordDlqMessage` 激活（首次采集不把历史量计入） |
| 3 | counter 首次增量漏报（increase 需 0 基线） | `BusinessMetrics.registerDlqCounter` 启动预注册 0 基线 |
| 4 | 告警重复 + 规则缺陷 | 规则改 `max by (consumer_group) (rocketmq_dlq_backlog) > 0`；`DlqMessageDetected` 改 `increase(myxhs_mq_dlq_total[10m]) > 0`（累计 counter 用 `>0` 会永久 firing） |
| 5 | topic 不存在时 gauge=-1（语义含糊，且**污染 counter 基线**：`prev=-1` 导致首条死信不计数） | 按 `ResponseCode.TOPIC_NOT_EXIST` 判定 + 实测文案 `Can not find Message Queue`/`route info` 兜底 → 视为 0；其他异常仍 -1 并 **WARN 一次**（原 debug 静默无法排查） |

## 五、运营处置
- 删除历史死信 topic（内容已在 09-17 报告归档 + 本次 printMsg 复核）：`%DLQ%cart-event-sink-group`、`%DLQ%cart-sync-consumer-group` → 删后告警转 resolved。
- 演练产生的 `%DLQ%notification-event-consumer-group` 已清理（2 条），清理后告警不再保持。

## 六、验证矩阵（修复后，全部实测）

| 项 | 结果 |
|---|---|
| 动态发现 | notification 启动日志"本服务 1 个 consumer group: [notification-event-consumer-group]"，只创建 1 个 PullConsumer |
| 历史死信清理 | 两条 topic 从 broker+NameServer 删除；cart gauge 语义 0（修复前 -1） |
| 毒丸→DLQ | 19:49:09 发毒丸 → 重试耗尽 → DLQ(topic max=1) → **gauge 0→1**、**counter 0→1**、日志"检测到新死信: 新增=1条" |
| 首增量告警 | Prometheus `increase(myxhs_mq_dlq_total[10m])=1.01` → **DlqMessageDetected firing**（修复前该告警永不触发） |
| 积压告警 | **RocketmqDlqBacklog firing**（"积压 2 条，持续 5 分钟"）；`max by` 后每组仅 1 条，无 15× 重复；清理后 resolved |
| 清理闭环 | 删除演练 DLQ topic → gauge 归 0；backlog 告警 resolved（counter 告警因 10m 增量窗口自然衰减） |
| 业务回归 | E2E **30/30**；API 07-14 **94/94** |

### 演练时间线（最终轮）
```
19:49:09 毒丸发送 → 消费失败重试
19:51:16 进 DLQ，日志"检测到新死信: 新增=1条"（counter 0→1）
19:51:20 RocketmqDlqBacklog pending（backlog=1）
19:51:29 DlqMessageDetected firing（increase≈1.01）
19:53:xx 删除演练 topic → gauge=0、backlog 告警 resolved
```

## 七、后续建议
- DLQ 重投闭环（第三级）目前是"报告归档 + 人工/审批重投"，建议把"重投后核验"做成脚本（参照 xhs-ai 的 `verified_consumed` 口径）。
- 死信清理应作为标准运维动作（本次已把"归档≠删除"的坑记录在此）。
