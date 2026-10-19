---
id: C-012
slug: 通知模块生产化改造
status: done
created: 2026-10-19
updated: 2026-10-19
iteration: ITER-012     # 机器可读迭代协议：.harness/iterations/ITER-012-通知模块生产化改造/
relations:
  relates_to:
    - slug: docs/学习/模块对照/09-通知
      reason: §7 七条线索的收口 + F1 回旋镖（C-009 过度纠正的 target_type 注释回正）
    - slug: docs/学习/模块对照/00-执行方案
      reason: Phase 3 消息域首站（notification——SSE 站内通知管道；im 为 C-013）
  depends_on: []
---

# C-012 通知模块生产化改造

> **立项（2026-10-19）**：Phase 3 消息域首站。**深探索（代码全读 20 文件 + echo 比对）**：
> 核心 = **F2 DLQ 缺口 ×2 组** + **F3 重试非幂等窗口（聚合计数双计）** + **F4 聚合窗口锁不自愈（PENDING 残留）** + **F6 订单状态通知 outbox 化（at-least-once）**；
> 另有 **F1 回旋镖**（C-009 把 t_notification.target_type 注释改错——对齐到了 counter 域语义，三份 SQL 副本自相矛盾）、F5 限流缺口 + 文档漂移、§7 死物/TTL/注释。
> 定位口径：**站内通知系统（SSE 单向实时通道），非 WebSocket（im=C-013）、非推送平台**；权威来源=列表 API，SSE 为实时加速。
> 规划详见 `.harness/iterations/ITER-012-通知模块生产化改造/`。

## 用户故事

作为消息域开发者，我想要把通知管道从"DLQ 卡死 + 重试双计 + 窗口锁不自愈 + 状态通知可丢"升级到
"**全兜底 + 重试幂等收敛 + 窗口自愈 + 订单通知 at-least-once**"，并把测试集从零建立、纳入回归。

## 非目标（Out of Scope）

- WebSocket/IM（im 模块 = C-013）；推送平台（APNs/FCM，无跨端离线通道需求）；
- 聚合结构性取舍（不写明细行）——设计决策保留（讲）；uk_aggregate NULL 理论缺口（无生产方，记录）；
- SSE 心跳逐连接串行发送优化（记录，讲）；社交通知 best-effort 口径（C-010 已记录，保持）。

## 契约影响

- REST: 无新增（三端点补 @RateLimit）
- MQ: 新增 DLQ 重放 ×2 组（notification-event / order-termination）；订单状态通知改走本地消息 outbox（topic 不变）
- 数据模型: 无 DDL（死表 DDL 注释标注，不删表）；order 侧复用 t_order_local_message（无新表）
- 文档: 09-通知.md §5 限流口径修正；sql/init-all.sql + deploy 副本 target_type 注释回正

## 影响面

- 模块/服务: my-xhs-notification（主体）；my-xhs-order（F6 状态通知 outbox 化，bounded）
- wiki: 无（归档时回流）

## 测试策略

- **测试集从零建立**（模块当前零测试目录）：消费者幂等/失败标记/重放去重、聚合矩阵（新建/聚合/PENDING/自愈/订单刷新）、未读 Lua、对账（游标/残留/日清）、SSE（连接/替换/清理/跨实例）、票据、DLQ 重放 ×2、限流注解
- 回归：**十二模块**（notification 首次纳入回归集）
