---
iteration: ITER-008
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 库存模块生产化改造

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - **幂等矩阵**：逻辑事件键（补发拦截/未处理放行/重试语义）——`InventoryDeductConsumerTest`（新）
  - **崩溃语义**：失败不置标记 / 成功后置标记（InOrder 断言）；L2 重复投递不双扣（tx 幂等）
  - **DLQ 重放**：`InventoryDlqReplayConsumerTest`（重投/tag 保留/超限/失败不吞）
  - **键模型**：每脚本 KEYS 同 tag 断言（键构造单测）+ 单键语义锁定（init 幂等/预扣/释放/确认/墓碑/退款原子）
  - **items 契约**：confirm/release 由 items 驱动；部分缺失写标记（F12）
  - **CAS**：快照条件更新参数断言
- **集成/E2E**：不做（无运行时）。
- **手工验证**：注释与部署基准对照（init-xxljob.sql:112）；台账/文档回写核验。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | 幂等键矩阵 + markSent/cancel key 匹配 | 单测 | isFirstProcess 调用键断言；dup 场景 SQL 参数 | 断言结果 |
| AC-2 | 完成态标记 + tx 幂等 | 单测 | InOrder（处理→标记）；重复投递第二次跳过库存变更 | 断言结果 |
| AC-3 | DLQ 重放单测 | 单测 | syncSend 目标 topic/tag + attempt 属性 + 告警路径 | 断言结果 |
| AC-4 | 键 tag 断言 + 单键语义矩阵 + items + F12 | 单测 | 全键同 tag；六链路语义；部分缺失写标记 | 断言结果 |
| AC-5 | CAS 参数断言 | 单测 | SQL 带快照条件 | 断言结果 |
| AC-6 | 注释核验 | 手工 | grep 零残留 + 基准对照 | 检查记录 |
| AC-7 | 指标/锁前缀核验 | 手工/单测 | recordInventoryAction 调用点；grep | 检查记录 |
| AC-8 | 八模块全量回归 | 单测 | 全绿 | 测试报告 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| 补发时钟错位（T0 vs created_at） | `sendInventoryEvent`/`InventoryOutboxSenderJob`（已核） | F1/F2 修复基准 |
| ON DUPLICATE 复用行 → eventId 失配 | Mapper SQL + DDL uk（已核） | markSent/cancel 改键依据 |
| 前置标记崩溃窗口 | 3 消费者代码（已核） | F4 修复基准 |
| 终止载荷含 items（TerminationItem{skuId,quantity}） | order 侧事件类（已核） | items 契约可行性 |
| order Feign 走 Map 载荷（易扩展） | order `InventoryFeignClient`（已核） | 跨模块改动面 |
| 部署 cron 基准（reconcile `0 0 * * * ?`） | init-xxljob.sql:112（已核） | F7 注释对齐 |
| `recordInventoryAction(action)` 通用指标已存在 | BusinessMetrics:83（已核） | F9 接线方式 |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
