---
iteration: ITER-007
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 优惠券模块生产化改造

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | syncSend 抛异常（超时） | claimCoupon | **不抛错**；outbox 保留（never markOutboxCancelled）；领券成功 | 是 |
| TC-2 | AC-1 | syncSend 返回非 SEND_OK 状态 | 同上 | 同上（保留 + 成功） | 是 |
| TC-3 | AC-1 | SEND_OK | 同上 | markOutboxSent 调用；成功 | 是 |
| TC-4 | AC-3 | SEND_OK 但 markOutboxSent 抛异常 | 同上 | 仅 warn；成功返回（不 cancel/不回滚） | 是 |
| TC-5 | AC-2 | outbox insert 首次抛异常、重试 DuplicateKey | 同上 | 按成功处理（行已存在）；成功返回 | 是 |
| TC-6 | AC-2 | outbox insert 双次失败 | 同上 | **回滚 Redis 调用 + BizException**（唯一回滚路径） | 是 |
| TC-7 | AC-4 | DB count = perUserLimit | 消费者 onMessage | 跳过（不 insert、不 decrement）+ 告警 | 是 |
| TC-8 | AC-4 | DB count < perUserLimit | 同上 | 正常 insert + decrement | 是 |
| TC-9 | AC-5 | DLQ 消息（attempt=0） | 重放消费者 | 重投源 topic（attempt+1） | 是 |
| TC-10 | AC-5 | DLQ 消息（attempt=3） | 同上 | **仅告警**（不重投） | 是 |
| TC-11 | AC-5 | 非法 DLQ 消息 | 同上 | 跳过/告警（不炸） | 是 |
| TC-12 | AC-6 | 全仓 | grep markOutboxCancelled | 零残留 | 是 |
| TC-13 | AC-6 | — | returnCoupon javadoc / claimCoupon 代码 | RV30 语义 javadoc；无重复 switch；无无意义 evict | 手工 |
| TC-14 | AC-7 | 用户有多张券（含：已下线模板/未生效/已过期/孤儿券） | getAvailableCoupons | 过滤语义与重构前一致（T-121 锁定） | 是 |
| TC-15 | AC-7 | 同上 | 查询次数断言 | 模板查询单次批量（N+1 消除） | 是 |
| TC-16 | AC-8 | — | 指标/cron 注释核验 | recordCouponAction 各事件落位；注释与 init-xxljob.sql 一致 | 手工 |
| TC-17 | AC-8 | 全仓 | grep COUPON_STOCK/COUPON_USER | 零残留 | 是 |
| TC-18 | AC-9 | 全仓 | 全量重编测试 | 八模块基线全绿 + 新增用例 | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-19 | AC-1 | 补发重复投递（同 claimNo 不同 msgId） | 消费者 uk_claim_no 幂等（既有行为不回归） | 是 |
| TC-20 | AC-4 | 守卫跳过后的对账收敛 | claimed 以 DB 为准修正（既有对账覆盖）——语义记录 | 是 |
| TC-21 | AC-2 | 重试时 insert 成功（首次实际未写） | 正常成功路径（行落库 + 发送） | 是 |
| TC-22 | AC-1 | 旧测试重写核验 | `claimCouponKeepsOutboxWhenMqSendFails` 语义与新实现一致（不再矛盾） | 是 |
