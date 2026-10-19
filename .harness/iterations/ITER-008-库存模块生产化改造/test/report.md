---
iteration: ITER-008
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 库存模块生产化改造

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-18（三阶段：① 可靠性 → ② 键模型 → ③ 工程；收口并入）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment,my-xhs-search,my-xhs-cart -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ 通过（BUILD SUCCESS；**457 全绿**：common 93 / product 35 / coupon 24 / order 124 / **inventory 32**（基线 20，+12）/ cart 33 / search 43 / payment 73——零回归）

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `InventoryDeductConsumerTest#duplicateApplicationSkipped`：inbox 命中 → 不双扣 | |
| TC-2 | AC-1 | 🟢 | `#firstApplicationApplies`：首次 → inbox 落账 + 变更 | |
| TC-3 | AC-1 | 🟢 | markSent/cancel 按 (order,sku,action)：`InventoryServiceTest`/`InventoryOutboxSenderJobTest` 断言 | F2 修复 |
| TC-4 | AC-2 | 🟢 | L1 崩溃语义：`OrderTransactionConsumerTest#shouldMarkProcessedAfterSuccess`（InOrder：先处理后标记）+ 重投补扣由 Lua 幂等（代码核验） | |
| TC-5 | AC-2 | 🟢 | 同上（完成后置标记） | |
| TC-6 | AC-2 | 🟢 | L2 重复投递：`#duplicateApplicationSkipped` | |
| TC-7 | AC-2 | 🟢 | L2 崩溃重投：事务回滚语义（`InventoryL2InboxService` 同事务设计 + `#firstApplicationApplies`） | |
| TC-8 | AC-2 | 🟢 | 终止消费者失败不置标记（`shouldNotMarkProcessedOnFailure` 同型 + 代码核验） | |
| TC-9 | AC-3 | 🟢 | `InventoryDlqReplayConsumerTest#replayResendsWithTag`：重投 + tag 保留 + attempt=1 | |
| TC-10 | AC-3 | 🟢 | `#replayStopsAtMaxAttempts`：超限仅告警 | |
| TC-11 | AC-3 | 🟢 | `#replaySendFailureThrows`：不吞 + 指标 fail | |
| TC-12 | AC-4 | 🟢 | `InventoryServiceTest#preDeductAllKeysSameSlot`：KEYS 全同 tag | |
| TC-13 | AC-4 | 🟢 | 单键语义矩阵：init/预扣/释放/确认/墓碑/退款（14 用例全绿） | |
| TC-14 | AC-4 | 🟢 | `#refundRestore_multiSkuUsesIndependentIdempotentKeys`：同槽原子脚本（KEYS=[refund,stock] 同 tag） | |
| TC-15 | AC-4 | 🟢 | `#confirmDeduct_partialMissing_writesConfirmedMarker`：F12 部分缺失写标记 | |
| TC-16 | AC-4 | 🟢 | items 契约：confirm/release 由 items 驱动（用例 + order 侧载荷代码核验） | |
| TC-17 | AC-4 | 🟢 | `PreDeductTimeoutJobTest`：per-sku 索引 SCAN → 逐 (sku,order) 释放/补确认 | |
| TC-18 | AC-5 | 🟢 | 对账 CAS：`updateAvailableStockCas`/`updateLockedStockCas`（SQL 条件核验） | |
| TC-19 | AC-6 | 🟢 | cron/时间/文案注释核验（ReconcileJob `0 0 * * * ?` 基准 init-xxljob.sql:112；TimeoutJob 1 分钟；Sender 文案） | |
| TC-20 | AC-7 | 🟢 | 指标（confirm/release/refund/outbox/补偿/对账/DLQ）落位；锁前缀统一 `myxhs:*`（grep） | |
| TC-21 | AC-8 | 🟢 | 八模块 457 全绿 + 全仓编译 | 本报告 |
| TC-22 | AC-1 | 🟢 | 原消息未投递 → 补发正常执行（inbox 无记录 → 应用；at-least-once 保持） | |
| TC-23 | AC-2 | 🟢 | L2 条件失败（0 行）→ 事件标记完成 + 失败事件（`releaseConditionFailureMarkedDone`） | |
| TC-24 | AC-4 | 🟢 | items 缺失防御：`releaseStock_missingItems_conservativeSkip` + confirm 写标记 | |
| TC-25 | AC-4 | 📝 | 旧键残留：清理说明（部署文档） | 手工 |
| TC-26 | AC-4 | 🟢 | 墓碑命中跳过：`preDeduct_terminatedTombstone_skip` | |
| TC-27 | AC-4 | 🟢 | 扩容/热点机制移除：无 pause/resize 残留（grep 零） | |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-008/ITER-008 ...` | prd/solution/test design/cases（逻辑事件幂等 + 去桶单键 + DLQ + F12） |
| ① | `feat(inventory): C-008① ...` | 逻辑事件幂等（L2 inbox + 完成态标记 ×2）+ Outbox 业务键匹配 + DLQ 重放 ×3（+10 测） |
| ② | `feat(inventory): C-008② ...` | 去桶单键 + per-(sku,order) 记录 + 单 slot + items 契约 + F12 + 退款原子脚本 + 对账 CAS + 删分桶全链（+2 测） |
| ③ | `feat(inventory): C-008③ ...` | 指标补齐 + 锁前缀统一 + Sender 文案 + reconcileLockedStock 单键化 |

## 深审查（实施期，证据链）

- 审查清单五维（幂等三场景/键一致性/语义保全/跨模块/证据）逐项闭环——详见 `test/review-c008.md`；
- **实施期抓到 1 个真 bug（R1：TimeoutJob 索引键格式与 Service 不一致 → 超时释放整体失效，测试抓到）+ 4 处遗漏（R2-R5）已修**；
- 语义保全：F-037/N10/T-073/T-124 随分桶消亡（等价成立）；04B 墓碑/标记/N7 保留并锁定。

## 收口清单

- [x] 八模块全量回归 457 全绿（零回归）+ 全仓编译
- [x] 深审查（review-c008.md：5 处发现全部修复）
- [x] 台账回写（问题 1/2/3 → 已修复；4/5 补标已修复）
- [x] 库存对照文档（模块对照系列补项）
- [x] 讲解系列修订记录（分桶移除）
- [x] change 卡 → done
- [x] 运行时验证（真实 MQ 重投/DLQ 重放/集群 slot）按项目规则后置
