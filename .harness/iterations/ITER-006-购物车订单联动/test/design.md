---
iteration: ITER-006
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 购物车订单联动（下单清车 + 取消回补）

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - **握手协议矩阵（本变更核心，AC-3）**：`CartOrderLinkageServiceTest`——正常序（clear→restore）/ 竞态两种到达序（restore→clear / 并发交错语义）/ 双向重复投递 / 直接购买单 / clear 失败自愈；
  - 消费者：`OrderCreatedCartConsumerTest` / `OrderTerminationCartConsumerTest`（消息解析、source 分支、失败抛错、trace）；
  - 服务/Lua：`CartServiceTest` 增量（removeBySkus 批量移除语义；RedisScript mock 惯例）；
  - order 侧：`OrderServiceTest`（buildTransactionPayload 含 source）+ 终结载荷 items（监听器单测——`OrderTerminationListenerTest` 增量）；
  - 可观测：指标计数断言（可选 SimpleMeterRegistry）或代码核验。
- **集成/E2E**：不做（无运行时）；e2e 脚本以语法 + 流程 review 口径。
- **手工验证**：前端 source 透传核验；cron 注释核验；§7 回写核验。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | 清车单测（source 分支/去重/批量移除） | 单测 | Redis 调用断言 + 标记断言 | 断言结果 |
| AC-2 | 回补单测（merge 调用/去重/checked） | 单测 | merge 调用断言 + 标记断言 | 断言结果 |
| AC-3 | **握手矩阵单测**（正常/竞态×2/重复×2/直接/自愈） | 单测 | 标记状态断言 + merge/remove 调用 | 断言结果 |
| AC-4 | order 载荷单测（source/items/eventVersion） | 单测 | payload 断言 | 断言结果 |
| AC-5 | 前端透传核验 | 手工 | 代码核验 | 检查记录 |
| AC-6 | 指标/日志核验（+可选计数断言） | 手工/单测 | 代码核验 | 检查记录 |
| AC-7 | cron 注释核验 | 手工 | 代码核验 | 检查记录 |
| AC-8 | cart 基线 + 八模块全量回归 | 单测 | 全绿 | 测试报告 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| 创建载荷字段（orderNo/userId/skuItems/couponId/timestamp） | `buildTransactionPayload`（已核） | 清车解析基准 |
| 终结载荷字段（eventVersion/orderId/orderNo/userId/couponId/reason/source/occurredAt/traceId） | `OrderTerminationEvent`（已核） | 回补解析基准 |
| 终结原因全集（5 类） | `TerminationReason`（已核） | 回补覆盖范围 |
| cart 基线（15 测全绿） | 基线运行（已核） | 八模块回归前置 |
| CartSyncEvent 动作=DELETE | `CartSyncEvent`（已核） | 同步事件规格（对照口径勘误） |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
