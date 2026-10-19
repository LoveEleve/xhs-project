---
iteration: ITER-009
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 计数与社交分析生产化改造

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - **颤振矩阵**：W1（follow+StepB 失败）与 U1（unfollow+StepB 失败）两场景 → 对账收敛（模拟多轮：修复后状态稳定）
  - **like 三环**：对账集合复制（SUNIONSTORE 调用断言）/ 完成态版本门（崩溃重投重处理）/ 权威重建（缺失→t_like 重建）
  - **版本门统一**：共用件行为矩阵（旧事件跳过 / 新事件推进 / 失败不推进 / 完成后单调）
  - **DLQ ×5**：重投/超限/失败不吞
  - **键 tag**：脚本 KEYS 同 tag 断言（键构造单测）
  - **工程债**：死代码 grep 零残留；注释/cron 与基准对照
- **集成/E2E**：不做（无运行时）。
- **手工验证**：cron 注释 vs init-xxljob.sql；镜像键读取方核验记录。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | 颤振双场景单测 | 单测 | 修复动作断言（ZADD/ZREM/INSERT/DELETE）+ 多轮收敛 | 断言结果 |
| AC-2 | 三环单测 | 单测 | SUNIONSTORE/版本门矩阵/重建调用 | 断言结果 |
| AC-3 | 共用件矩阵 + 消费者替换 | 单测 | 四态行为断言 | 断言结果 |
| AC-4 | DLQ 单测 | 单测 | 目标 topic/tag + attempt 属性 + 告警 | 断言结果 |
| AC-5 | 镜像键核验 | 手工+单测 | grep 记录 + 移除后回归 | 检查记录 |
| AC-6 | 键 tag 断言 | 单测 | KEYS 全同 tag | 断言结果 |
| AC-7 | 死代码/注释/cron/守卫/限流 | 单测+手工 | grep + 对照 + 限流注解断言 | 检查记录 |
| AC-8 | 十模块全量回归 | 单测 | 全绿 | 测试报告 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| 颤振双向证据链（Step A 事实源 + 两修复方法） | `FollowService` 84-211/513-647（已核） | 修复基准 |
| like 三环（守卫① 已止血 / ② 只修值 / ③ reserve-first） | `CounterService` 348-420 + 5 消费者（已核） | 修复基准 |
| 版本门 4+ 拷贝位置 | counter 消费者 + LikeUnlike/FavoriteUnlike 内联（已核） | 统一范围 |
| DLQ 五组名（消费者组名） | 5 消费者注解（执行时核） | 重放 topic |
| 镜像键无读取方（初判） | 08 §7-1 + grep（执行时复核） | 移除决策 |
| cron 基准（counter hourly / follow */10） | init-xxljob.sql:163/157（已核） | 注释对齐 |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
