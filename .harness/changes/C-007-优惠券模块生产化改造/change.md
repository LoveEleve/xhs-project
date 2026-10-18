---
id: C-007
slug: 优惠券模块生产化改造
status: confirmed
created: 2026-10-17
updated: 2026-10-17
iteration: ITER-007     # 机器可读迭代协议：.harness/iterations/ITER-007-优惠券模块生产化改造/
relations:
  relates_to:
    - slug: docs/学习/模块对照/03-优惠券
      reason: §7 十条线索的收口（含 lead5 翻案）+ 7 个新发现
    - slug: docs/学习/订单-讲解/04B-订单终结
      reason: ORDER_TERMINATION_TOPIC 退券消费（既有链路核验）
  depends_on: []
---

# C-007 优惠券模块生产化改造

> **立项（2026-10-17）**：模块对照阶段二第三站（交易域收官）。深度探索 + **两轮 review（34 项走查）+ 业务/产品语义对照**：
> 核心 = **领券发送链的"部分失败→超发"洞**（Design B：至少一次语义重构）；另 1 个语义级缺口（G35 投递时限领守卫）。
> lead5（SQL 库段错位）**翻案**（对照文档误判）；cron 真相三方对照核实。
> 规划详见 `.harness/iterations/ITER-007-优惠券模块生产化改造/`。

## 用户故事

作为优惠券域开发者，我想要把领券链路从"内部故障暴露为用户失败 + 超发洞"升级到"**名额锁定即成功、券必达（至少一次）**"，
并补齐 DLQ 兜底/指标/注释漂移，以便学习的是最终形态，且与订单/库存/支付/商品/购物车的改造水准对齐。

## 非目标（Out of Scope）

- 叠加/互斥/发券/兑换码/定向券（应然缺口——讲不做）
- 重型一致方案（版本号 CAS 等——outbox+对账已够，克制）
- 权限类；AI 直读相关

## 验收标准

- AC-1 ~ AC-9 见 `.harness/iterations/ITER-007-优惠券模块生产化改造/prd.md`（本变更走迭代协议）

## 边界情况

- 发送异常/非 SEND_OK（消息可能已存）→ **保留 outbox + 领券成功**（补发必达；重复投递由 uk_claim_no 幂等）
- outbox 写入"超时≠失败" → 同 claimNo 幂等重试一次（DuplicateKey=实际已写）；仍失败才回滚（残余窗口收窄）
- DLQ 重放 × 重领交叉 → **投递时限领守卫**（count ≥ perUserLimit → 跳过+告警；stock 漏损安全方向）
- 退券永久失败 → 既有 HITL 重投工具路径（dlq.redeliver）；对账修 claimed 后用户可再领

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | getAvailableCoupons 消除 N+1（单次批量模板查询复用）；claim 路径无新增往返 |
| 可靠性 | 至少一次（outbox+补发+DLQ 重放）；无超发向量；漏损安全方向 |
| 可观测 | recordCouponAction 指标族（claim/use/return/DLQ/reconcile）；cron 注释与部署一致 |

## 设计约束

- 产品语义锚定：领券 = **名额锁定即成功 + 券必达**；内部故障不进用户视野（对齐真实平台）
- 生产标准：业务/工程/分布式/微服务/性能/可观测全维度；不改文章正文（对照 §7 回写）

## 契约影响

- REST: 无新增（claim 失败语义收窄：仅业务结果 + outbox 双次写入失败）
- MQ: 新增 DLQ 重放消费者（消费两个 `%DLQ%` topic）；无新业务 topic
- 数据模型: 无 DDL

## 影响面

- 模块/服务: my-xhs-coupon（主体）；my-xhs-common（死常量删除）
- wiki: 无（归档时回流）

## 测试策略

- 先写失败测试：Design B 各分支（发送异常→保留+成功 / 非OK→保留 / 写入双败→回滚）；投递时限领守卫；DLQ 重放（attempt 上限）；getAvailableCoupons 语义锁定（T-121）
- 回归：八模块基线保持全绿
