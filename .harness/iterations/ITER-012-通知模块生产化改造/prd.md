---
iteration: ITER-012
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# PRD: 通知模块生产化改造

## 背景与目标

**深探索结论**（代码全读 20 文件 / 2208 行 + 与前几轮 echo 比对）：

- **🔴 F1 回旋镖**：`t_notification.target_type` 注释被 C-009 改错——`sql/init-all.sql` + deploy 副本写成"1-笔记 2-用户 3-评论 4-商品"（对齐到了 **counter 域**的 targetType 语义），而 DTO/全部生产方/另两份 SQL 副本都是"**1-笔记 2-商品 3-订单**"（like=1 / order=3 / follow=null）→ 三副本自相矛盾；
- **🔴 F2 DLQ 缺口 ×2 组**：notification-event / order-termination 两消费者 `maxReconsumeTimes=3` 后进死信**无人重放**（C-007/8/9/10/11 全族已补）；
- **🔴 F3 重试非幂等窗口**：catch-all `removeMark`（违反 MessageIdempotentHelper 自身契约"仅确定未执行成功时删"）+ 聚合计数非 msgId 幂等 → Redis 抖动（unread 增量/读计数抛异常）→ MQ 重试 → **aggregate_count 双计**（unread 有 Objects.equals 守卫不双计）；
- **🔴 F4 聚合窗口锁不自愈**：SETNX→INSERT 间失败（DB 异常）→ 窗口键残留 PENDING 至当天结束 → 后续同窗口全部走"独立插入"→ 当天聚合失效；commit 失败/行被删（affected≤0）同理不自愈；
- **🟡 F6 订单状态通知 fire-and-forget**：`publishStatusChanged` asyncSend 失败仅日志（vs 终结通知走 Outbox）——交易类通知不该丢（聚合刷新使中间态可自愈、最终态不可）；
- **🟡 F5 限流缺口 + 文档漂移**：09-通知.md §5 称端点"均已限流"——实际仅 3 个 read 端点有 @RateLimit，list/unread-count/sse/ticket 无；
- §7-1 javadoc"5 分钟窗口" vs 实际自然日；§7-2 死物（t_push_task + **t_push_task_fail** 死表、updateAggregateInfo 死方法、is_aggregated/aggregate_id 死字段）；§7-3 forceSetUnread 无 TTL；§7-7 脚本未静态复用；
- 记录（讲）：§7-4 聚合不写明细（存储层聚合）、§7-5 uk_aggregate NULL、§7-6 对账成本、SSE 心跳串行、社交通知 best-effort 口径。

## 非目标

WebSocket/IM（C-013）；推送平台（APNs/FCM）；聚合结构性重设计；SSE 心跳批量优化。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **DLQ 兜底（F2）**：DLQ 重放消费者 ×2（notification-event / order-termination）——重投源 topic，保留 KEYS + 原 msgId 属性；attempt≤3 防循环；重投失败不吞 | P0 |
| REQ-2 | **重试幂等收敛（F3）**：post-commit 步骤（未读增量/推送计数读取）失败**不抛出**（log 降级 + 10min 对账兜底）→ 消除"重试重跑聚合"的双计窗口；消费者幂等键**优先原 msgId 属性**（重放去重生效） | P0 |
| REQ-3 | **窗口锁自愈（F4）**：三路径删键重选——① INSERT 异常（catch 删键）② PENDING 超时回退（删键 + 独立插入）③ increment affected≤0（键指向已回滚行 → 删键 + 独立插入） | P0 |
| REQ-4 | **订单状态通知 outbox 化（F6）**：order 侧复用 t_order_local_message（04B 基建）——新增 operation 类型 + 补发 topic 映射；发送改"落库 + 提交后发送/失败补发"→ at-least-once | P0 |
| REQ-5 | **限流与口径（F5）**：list 30/min、unread-count 60/min、sse/ticket 10/min（perUser）；/sse 建连保持"票据一次性 + 50k 帽"（口径写明）；09-通知.md §5 修正 | P1 |
| REQ-6 | **工程清理（F1/§7）**：target_type 注释回正 ×2（init-all + deploy 副本）；死物清理（死方法 + 死字段；死表 DDL 注释标注）；javadoc 修正（5 分钟→自然日；mapper 返回值漂移）；forceSetUnread 补 30d TTL；脚本静态复用 | P1 |
| REQ-7 | **收口**：测试集从零建立（≥30）+ 十二模块回归 + §8 回写（09-通知）+ 面试材料同步 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | DLQ 重投 ×2（KEYS/原 msgId 保留；attempt≤3；失败不吞）（单测） | F2 |
| AC-2 | REQ-2 | post-commit 失败不触发重试（吞 + 对账兜底断言）；重放已处理消息被原 msgId 去重（单测） | F3 |
| AC-3 | REQ-3 | 三路径自愈：INSERT 失败删键 / PENDING 超时删键 / affected≤0 删键 → 重试重选窗口（单测） | F4 |
| AC-4 | REQ-4 | 状态通知落库 + 发送 + 补发映射（单测）→ at-least-once | F6 |
| AC-5 | REQ-5 | 三端点 @RateLimit 落位 + 文档修正（核验） | F5 |
| AC-6 | REQ-6 | 注释回正 ×2 + 死物零残留 + TTL + 静态脚本（grep/单测） | F1/§7 |
| AC-7 | REQ-7 | 测试集 ≥30 + 十二模块全绿 + §8/材料回写 | 回归 |

## 边界情况

- DLQ 重放历史积压（修复前部分成功消息）→ 原 msgId 保留使重放被去重（安全）；
- 聚合 post-commit 失败 → 未读由 10min 对账收敛（红点最多滞后 10 分钟，口径写明）；
- 订单状态通知落库失败 → 不影响交易主链路（补发 Job 兜底；同 04B 终结口径）；
- 窗口删键与并发事件 → 最坏窗口产生 2 条独立行（可接受，不丢事件）。

## 非功能需求

无运行时验证（编译 + 单测）；集群基线（Redis Cluster——未读双键已有 {u:id} tag）；无新增外部依赖。
