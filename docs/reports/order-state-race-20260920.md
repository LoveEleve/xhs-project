# 订单状态机 × 并发竞态对抗测试（2026-09-20）

> 方法：真实系统对抗序列（经网关 + JWT + HMAC），并发用线程 Barrier 同发；终态用 API + DB（t_order_event/t_refund）+ 库存/券核对。
> 脚本：`scripts/test-order-state-race.py`（可重跑）；证据：`docs/reports/order-state-race-run-*.json`。

## 一、场景与结果（8/8 通过）

| # | 对抗场景 | 终态证据 | 判定 |
|---|---|---|---|
| S1 | 并发重复支付回调 ×5 | status=1；事件仅 CREATED+PAID（无重复）；5 次回调幂等 | ✓ |
| S2 | 取消 vs 支付同发 | status=4（取消赢）；**自动退款** REFUND…003 已成功（status=1） | ✓ |
| S3 | 重复退款回调 ×3（+模拟器） | status=5；仅 1 条 ORDER_REFUNDED；退款单 1 条成功 | ✓ |
| S4 | 确认收货 vs 退款同发 | status=5；事件 CREATED→PAID→DELIVERED→COMPLETED→REFUNDED（无跳变） | ✓ |
| S5 | 部分退款（100/实付199） | 订单保持 1、支付单保持 1（累计<全额不通知订单） | ✓ |
| S6 | 支付成功但订单已取消 | 支付**创建阶段**即拒绝（"订单状态非待付款"）→ 无资金损失 | ✓ |
| S7 | 同 bizIdentifier 并发创建 ×3 | 仅 1 单（幂等键生效） | ✓ |
| S8 | 两笔部分退款累计=全额（100+99） | 订单 **5**、支付单 **3**、库存完整回补（available+1/locked-1） | ✓ |

## 二、机制印证（代码级）

1. **幂等事件 + 乐观锁**：`OrderEventService.appendEvent` 用 `INSERT IGNORE + uk_order_event_seq` 防重复事件，`updateStatusWithLock` 乐观锁防并发覆盖；重复回调/重复事件只收敛状态不重复插入。
2. **取消 vs 支付**：支付服务对订单侧业务拒绝（已取消）执行 **P1-1 自动退款**，且退款成功后才算闭环（实测 REFUND 状态 1）。
3. **退款累计口径**：`getRefundedAmount` 仅累计 status=1 的退款；累计=支付金额才置支付单 3 + 通知订单（Feign + REFUND_RESULT_TOPIC 双通道）；部分退款不通知订单。
4. **退款幂等锁**：Redisson 锁 + Redis refunding 标记，处理中重复请求返回 `40201 请勿重复退款`；失败后标记清理，可重新发起。

## 三、发现（无功能性缺陷，登记 2 项运营/口径项）

1. **渠道退款失败不自动重试**（mock 模拟器 10% 失败率触发过一例）：`handleRefundFailInternal` 置 FAIL 并通知订单，重试需人工/重新发起。生产建议：加"失败退款自动重试 + 次数上限 + 告警"（当前仅有退款超时检查与通知补偿 Job）。**本轮不改代码，登记待决策**。
2. **注释漂移**：`PaymentService` 中 "MQ无消费端" 措辞已过期（REFUND_RESULT_TOPIC 有消费者）→ 已修正。
3. 测试数据说明：S7 的待支付订单将由 30min 延时关单自动清理；其余订单为已支付/已退款状态，属正常测试数据。

## 四、验证口径小结（面试可讲）
- 对抗测试不看"跑通"，看**终态一致性**：状态机终态、事件序列、资金（退款单累计）、库存（available/locked）、券五者互证；
- 竞态结论：**单赢家 + 幂等收敛 + 补偿闭环**（取消赢→自动退款；退款赢→订单 5；重复回调→单事件）。

## 五、关联
- 脚本与证据：`scripts/test-order-state-race.py`、`order-state-race-run-20260920-001637.json`
- 相关题：xhs/03 库存、04 TCC、05 事务消息、06 幂等、07 乱序、29 订单域、30 支付域
