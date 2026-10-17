---
iteration: ITER-002
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-16
updated: 2026-10-16
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 支付模块生产化改造

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - 复用/换渠道/幂等：`PaymentService` 单测（Mockito；JdbcTemplate 以 mock 或内嵌 SQL 断言）；
  - 超时处置矩阵：`checkPaymentTimeout` 三分支单测（渠道成功/未付/不可达）；
  - 对账核查分支：未核实失败单重查渠道收敛（AC-9）；
  - **存量用例改写**：`PaymentServiceTest#paymentDuplicatePrevention`（防重拦截断言）→ 复用断言；
  - 回归：全模块全量重编测试（删 `target/test-classes`）。
- **集成/E2E**：不做（无运行时）；渠道交互以策略 mock 断言调用序列。
- **手工验证**：迁移脚本 review（存量清理 SQL）。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | 复用单测（同渠道二次发起/并发 DuplicateKey 兜底） | 单测 | paymentNo 相同；pending ≤1 | 断言结果 |
| AC-2 | 换渠道单测（close 调用序列 + status=4 + 新单） | 单测 | 策略 close 被调；状态断言 | 断言结果 |
| AC-3 | 退款幂单单测（同键返回同 refundNo；并发唯一索引兜底） | 单测 | refundNo 相同；退款单数不变 | 断言结果 |
| AC-4 | 超时矩阵单测（成功/未付/不可达三分支） | 单测 | 状态处置断言 + 不处置分支 | 断言结果 |
| AC-5 | 证据单测（verify_method 落库 + close 调用） | 单测 | 字段断言 | 断言结果 |
| AC-6 | 咬合单测（订单终结 → close-pending 调用） | 单测 | 调用断言 + 状态断言 | 断言结果 |
| AC-7 | 配置单测（改配置影响行为）+ 模拟器参数注入断言 | 单测 | 行为随配置变化 | 断言结果 |
| AC-8 | 全模块基线回归（251+）+ 新增用例 | 单测 | 全绿 | 测试报告 |
| AC-9 | 对账核查分支单测（未核实失败单重查渠道 → 已付则收敛） | 单测 | 状态断言 + 通知断言 | 断言结果 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| （无：离线单测口径） | — | — |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
