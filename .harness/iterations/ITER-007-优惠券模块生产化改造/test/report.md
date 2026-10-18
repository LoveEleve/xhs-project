---
iteration: ITER-007
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-18
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 优惠券模块生产化改造

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-18（三阶段：① 领券可靠性重构 → ② 一致性与兜底 → ③ 可观测与工程；收口并入）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment,my-xhs-search,my-xhs-cart -am test`
- 结果: ✅ 通过（BUILD SUCCESS；**445 全绿**：common 93 / product 35 / **coupon 24**（基线 11，+13）/ order 124 / inventory 20 / payment 73 / search 43 / cart 33——零回归）
- 注：首轮汇总曾混入 2 个陈旧 surefire 报告（`FaultInjectionTest` 9 月失败报告 + 已删除的 `CouponReturnRedisRepairConsumerTest`）→ 清理后按本次运行报告统计

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `CouponServiceTest#claimCouponKeepsOutboxWhenMqSendFails`：发送异常→不抛错 + outbox 保留 + never 回滚 | Design B 核心 |
| TC-2 | AC-1 | 🟢 | `#claimCouponKeepsOutboxOnNonOkStatus`：非 SEND_OK→保留 + never markSent + never 回滚 | |
| TC-3 | AC-1 | 🟢 | `#claimCouponSuccess`：SEND_OK→markSent 调用 + 成功 | |
| TC-4 | AC-3 | 🟢 | `#claimCouponMarkSentFailureStillSuccess`：markSent 抛异常→仅告警 + 成功 + never 回滚 | |
| TC-5 | AC-2 | 🟢 | `#claimCouponOutboxInsertRetryHitsDuplicate`：首次失败→重试 DuplicateKey→按成功 | 写入超时≠失败 |
| TC-6 | AC-2 | 🟢 | `#claimCouponOutboxDoubleFailureRollsBack`：双败→回滚（returnCouponScript）+ BizException + never syncSend | 唯一回滚路径 |
| TC-7 | AC-4 | 🟢 | `CouponClaimConsumerTest#limitGuardSkipsWhenAlreadyAtLimit`：count≥limit→不 insert/不 decrement + 守卫指标 | G35 |
| TC-8 | AC-4 | 🟢 | `#limitGuardPassesWhenBelowLimit`：未达限领→正常发券 + persist_ok 指标 | |
| TC-9 | AC-5 | 🟢 | `CouponDlqReplayConsumerTest#replayResendsToSourceTopic`：重投源 topic + attempt=1 属性 | |
| TC-10 | AC-5 | 🟢 | `#replayStopsAtMaxAttempts`：attempt=3→仅告警（over_limit 指标）+ never syncSend | 防循环 |
| TC-11 | AC-5 | 🟢 | `#replaySendFailureThrows`（重投失败抛异常不吞）+ `#replayInvalidAttemptTreatedAsZero`（非法属性按 0） | |
| TC-12 | AC-6 | 🟢 | grep `markOutboxCancelled` 零调用残留（仅留删除说明注释） | 代码核验 |
| TC-13 | AC-6 | 🟢 | returnCoupon javadoc=RV30 语义（不回补/幂等）；claimCoupon 无重复 switch（dispatchClaimResult 提取）；returnCoupon 无 evictTemplateCache | 代码核验 |
| TC-14 | AC-7 | 🟢 | `#getAvailableCouponsSemanticsLocked`：下线/孤儿/未生效/已过期四类过滤 + 保留有效券（T-121 语义） | |
| TC-15 | AC-7 | 🟢 | 同用例：`selectBatchIds` times(1) + `selectById` never（N+1 消除） | |
| TC-16 | AC-8 | 🟢 | 指标族落位（claim_ok/sold_out/limit/fail、use_ok/fail、return_ok/skip、persist_ok/fail/duplicate、limit_guard_skip、dlq_replay_ok/fail/over_limit）；cron 注释 ×2 对齐 init-xxljob.sql:118/123 | 代码核验 |
| TC-17 | AC-8 | 🟢 | grep `COUPON_STOCK`/`COUPON_USER` 零残留（RedisKeyConstants 已删） | |
| TC-18 | AC-9 | 🟢 | 八模块 445 全绿 + 全仓编译 | 本报告 |
| TC-19 | AC-1 | 🟢 | 重复投递幂等：`CouponClaimConsumerTest#stockDecrementFailureFailsConsumption`（既有行为不回归）+ uk_claim_no/DuplicateKey 分支核验 | |
| TC-20 | AC-4 | 🟢 | 对账收敛语义：`CouponReconcileJob` 以 DB countIssued 为准修 claimed（代码核验） | |
| TC-21 | AC-2 | 🟢 | `#claimCouponOutboxInsertRetrySucceeds`：首次失败→重试成功→正常发送 + markSent | |
| TC-22 | AC-1 | 🟢 | 旧测试 `claimCouponKeepsOutboxWhenMqSendFails` 已重写——名称/语义与新实现一致（不再矛盾） | 探索期 N2 |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-007/ITER-007 ...` | prd/solution/test design/cases（Design B + 守卫 + DLQ 重放 + 记录不做） |
| ① | `feat(coupon): C-007① ...` | sendClaimEventSync Design B 重构；markOutboxCancelled 删除；重复 switch 提取；测试矩阵 5 例（+TC-21 后补） |
| ② | `feat(coupon): C-007② ...` | G35 守卫 + 指标；DLQ 重放消费者（5 例）；getAvailableCoupons 消 N+1（+语义锁定）；returnCoupon javadoc/清理 |
| ③ | `feat(coupon): C-007③ ...` | 服务侧指标族；cron 注释对齐（含 `*/5` javadoc 转义）；死常量删除 |

## 深审查与语义对照（探索期，证据链）

- 两轮 review 34 项走查（流程清单 → 逐步思考）：G3（outbox 写入超时≠失败→幂等重试）、G-H1/H2（markSent 失败/非 OK 不得回滚）、G25（DLQ 重放防循环）、G35（投递时限领守卫）；
- 残余窗口确认：**连续两次写入失败**才回滚（可证"无消息在途"）；概率极低、对账可见——记录；
- 守卫触发条件 = "Redis 闸门与 DB 分歧"（正常路径零影响）；stock 漏损为安全方向；
- lead5 翻案：init-all.sql:525-526 明确 `USE my_xhs_content`——"SQL 库段错位"为 phase-1 误判；
- cron 三方对照：deploy zip init-xxljob.sql:118/123 为部署基准（09-18 审计报告佐证）。

## 第三轮深审查（查漏补缺，2026-10-18）

- 清单先行 19 项（部分失败/分布式/数据正确性/工程/证据五维）→ 逐项代码级校验；详见 `test/review-c007.md`；
- 结论：**零逻辑缺陷**（Design B / 守卫 / DLQ 重放实现自洽——A1 uk 前提、A2 双保险、A5 对账自愈、B4 收敛方向均有证据）；
- 修复 6 处注释/文案漂移：Sender/Reconcile/Expire 类级 javadoc 旧叙事 ×3、outbox DDL status 注释 ×2 副本、DLQ 内嵌类冗余 @Slf4j ×2、对账文案"个模板"→"项"；
- 复核证据：全仓编译零错误 + 八模块 445 全绿（审查后重跑）。

## 收口清单

- [x] 八模块全量回归 445 全绿（零回归；第三轮审查后复核）
- [x] 第三轮深审查（19 项，review-c007.md）
- [x] §7 回写（`docs/学习/模块对照/03-优惠券.md` §8：10 线索裁决含 lead5 翻案 + 新发现 + 核心设计 + 记录不做）
- [x] change 卡 → done
- [x] 运行时验证（真实 MQ DLQ 重放/守卫触发）按项目规则后置
