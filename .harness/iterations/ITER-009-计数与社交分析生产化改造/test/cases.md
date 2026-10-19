---
iteration: ITER-009
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 计数与社交分析生产化改造

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | W1：MySQL 有行 (A,B)、B 粉丝 ZSet 缺 A、A 的 FOLLOW_LIST 含 B | repairFollowerRelationships(B) | **ZADD 回 B 粉丝 ZSet**（不删 MySQL）；多轮后稳定 | 是 |
| TC-2 | AC-1 | U1：MySQL 无行、B 粉丝 ZSet 有 A、A 的 FOLLOW_LIST 缺 B | 同上 | **ZREM B 粉丝 ZSet**（不插 MySQL）；多轮后稳定 | 是 |
| TC-3 | AC-1 | MySQL-only + follower 列表整体缺失 | 同上 | 保守跳过（不删不建） | 是 |
| TC-4 | AC-1 | 残留场景 U2（真取关、MySQL 删失败） | 同上 | 删 MySQL 行（现状语义保留） | 是 |
| TC-5 | AC-2 | counter 值 ≠ analytics SCARD（Set 存在） | reconcileLikeFromAnalytics | 修值 + **SUNIONSTORE 复制集合** + EXPIRE | 是 |
| TC-6 | AC-2 | analytics Set 缺失、t_like 有行 | 同上 | **从 t_like 重建 Set** 再比对 | 是 |
| TC-7 | AC-2 | analytics Set 缺失、t_like 无行 | 同上 | 真 0（跳过/清零口径） | 是 |
| TC-8 | AC-2 | 版本门完成态：处理成功 | 事件消费 | 完成后单调推进 | 是 |
| TC-9 | AC-2 | 崩溃（无推进） | 重投 | 重新处理（不丢）；重处理幂等（唯一索引） | 是 |
| TC-10 | AC-3 | 旧事件（version ≥ actionTime） | 消费 | 跳过 | 是 |
| TC-11 | AC-3 | 新事件 | 消费 | 应用 + 完成后推进 | 是 |
| TC-12 | AC-3 | 处理失败 | 消费 | 不推进（重投可重处理） | 是 |
| TC-13 | AC-4 | DLQ 消息（5 组任一，attempt=0） | 重放 | 重投源 topic + attempt=1 | 是 |
| TC-14 | AC-4 | attempt=3 | 重放 | 仅告警不重投 | 是 |
| TC-15 | AC-5 | 镜像键读取方核验 | grep 全仓 | 无读取方→移除后回归全绿；有→改语义 | 手工 |
| TC-16 | AC-6 | 各脚本 KEYS 构造 | 键断言 | 全部同 tag（{userId}/{targetId} 家族） | 是 |
| TC-17 | AC-7 | 死代码 6 处 | grep | 零残留 | 是 |
| TC-18 | AC-7 | cron 注释 ×2 / DDL 注释 / 前缀 | 对照 | 与基准一致 | 手工 |
| TC-19 | AC-7 | 脏值（batchGet/reconcile） | 单测 | 守卫跳过不炸批 | 是 |
| TC-20 | AC-7 | /batch-get | 限流断言 | @RateLimit 落位 | 是 |
| TC-21 | AC-8 | 全仓 | 十模块全量重编测试 | 全绿（coupon 24 / inventory 33 基线起 + counter/analytics 新基线） | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-22 | AC-1 | 粉丝侧修复与计数修复联动 | 计数按修复后 ZCARD 收敛 | 是 |
| TC-23 | AC-2 | 大集合超重建上限 | 跳过 + 告警（不清零） | 是 |
| TC-24 | AC-2 | like:set 懒迁移 TTL | 迁移出的集合补 TTL | 是 |
| TC-25 | AC-3 | 版本 key TTL 过期后重投 | 无版本 → 正常处理（幂等兜底） | 是 |
| TC-26 | AC-5 | 镜像键移除后 counter FOLLOW 事件 | 正常消费（跳过处理）不报错 | 是 |
| TC-27 | AC-6 | 旧键（无 tag）残留 | 新逻辑不受影响（清理说明） | 手工 |
