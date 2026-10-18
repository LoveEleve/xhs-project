---
iteration: ITER-007
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md / solution.md；本文件 confirmed 前不得进入 coding
---

# 测试设计: 优惠券模块生产化改造

> 机器可读的迭代协议产物之一。定义**验证策略与观测方式**，是 test/cases.md 与 test/report.md 的上游。

## 验证策略

- **单测为主**（项目规则：无运行时环境；编译 + 单测为验证口径）：
  - Design B 三分支矩阵：`CouponServiceTest`（发送异常→保留+成功 / 非OK→保留 / SEND_OK+markSent 失败→成功 / outbox 双败→回滚+抛错 / DuplicateKey 重试=成功）
  - 限领守卫：`CouponClaimConsumerTest`（超限跳过 / 正常放行 / 守卫不触发时行为不变）
  - DLQ 重放：`CouponDlqReplayConsumerTest`（重投 / attempt 超限告警 / 非法消息）
  - N+1 消除语义锁定：`CouponServiceTest`（T-121：validStart/End/下线/孤儿过滤——重构前后行为一致）
  - 清理核验：`markOutboxCancelled` 零残留（grep + 编译）；javadoc/cron 注释/死常量
- **集成/E2E**：不做（无运行时）。
- **手工验证**：cron 注释与 `init-xxljob.sql:118/123` 对照；§7 回写核验。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 | 证据类型 |
|----|---------|------|---------|---------|
| AC-1 | 发送异常/非OK 单测 | 单测 | 不抛错 + outbox 保留断言（never cancel） | 断言结果 |
| AC-2 | outbox 双败/重试 DuplicateKey 单测 | 单测 | 回滚调用 + BizException / 成功路径 | 断言结果 |
| AC-3 | SEND_OK + markSent 失败单测 | 单测 | 仅 warn + 成功返回 | 断言结果 |
| AC-4 | 限领守卫单测（超限/正常） | 单测 | 跳过断言 + count 查询断言 | 断言结果 |
| AC-5 | DLQ 重放单测（重投/超限/非法） | 单测 | syncSend 调用 + 告警路径 | 断言结果 |
| AC-6 | 死方法清理 + javadoc/重复代码核验 | 单测/手工 | grep 零残留 + 代码核验 | 检查记录 |
| AC-7 | getAvailableCoupons 语义锁定（T-121） | 单测 | 过滤行为断言（重构前后一致） | 断言结果 |
| AC-8 | 指标/cron 注释核验 | 手工 | 代码核验 + init-xxljob.sql 对照 | 检查记录 |
| AC-9 | 八模块全量回归 | 单测 | 全绿 | 测试报告 |

## 动态事实接入

| 事实 | 来源 | 用途 |
|------|------|------|
| 发送链现状（SEND_OK→markSent / 失败→cancel+rollback） | `sendClaimEventSync`（已核） | 重构基准 |
| markOutboxCancelled 引用面（mapper + 2 调用点） | grep（已核） | 删除范围 |
| cron 部署真相（expire 5min / reconcile hourly） | `init-xxljob.sql:118/123` + 09-18 审计（已核） | 注释对齐基准 |
| 测试矛盾（claimCouponKeepsOutboxWhenMqSendFails 命名 vs 实现） | 测试文件（已核） | 重写依据 |
| 消费-DLQ 先例（核心线无自动重放；AI 线 HITL） | 全仓 grep（已核） | 设计依据 |

## 证据回填约定

- 每次执行回填：模块 / 测试类 / 断言结果 / 执行时间
- 以 Maven 测试输出（surefire 摘要）为证据
