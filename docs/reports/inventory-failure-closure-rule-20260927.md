# 库存失败收口规则与 Runbook（v1，2026-09-27）

> 背景：库存预扣/确认在 MySQL 侧重试耗尽后，原实现"只 log、等对账"，订单侧无感知。
> 风险：**订单已提交（甚至已支付）但库存从未锁定** → 发货超卖 → 只能售后赔付。
> 本规则落地"失败事件 + 订单侧收口 + 人工兜底"闭环（P0 修复 v1）。

## 一、收口策略（三态）

| 订单状态 | 触发 | 动作 | 指标 |
|---|---|---|---|
| **待付款(0)** | 收到 `INVENTORY_FAILED_TOPIC` | **自动取消**（原因=库存预扣失败；释放其他已成功 SKU、退券、通知用户） | `order_inventory_failure_total{result="auto_cancelled"}` |
| **已付款(1)** | 同上 | **不自动退款（v1）**：记 ERROR + 指标 → 告警触发 → 人工/对账介入 | `order_inventory_failure_total{result="manual_required_paid"}` |
| 其他状态 | 同上 | 幂等跳过 | `..._total{result="skipped_status_X"}` |

- 事件链：`inventory`（预扣/确认失败）→ `INVENTORY_FAILED_TOPIC` → `order-inventory-failed-consumer-group` → `OrderService.handleInventoryFailure`。
- 失败事件仅覆盖 **PRE_DEDUCT / CONFIRM**（资金相关）；RELEASE / REFUND_RESTORE 失败只影响 MySQL 镜像，由 L3 对账（Redis 为 truth）修复。
- 发布尽力而为（syncSend 3s + 失败指标），不引入 Outbox——因为订单侧还有 **30 分钟超时关单**与 **对账** 双兜底。

## 二、监控与告警

| 指标 | 含义 |
|---|---|
| `inventory_failure_total{reason}` | 库存侧失败：`PREDUCT_FAILED` / `CONFIRM_FAILED`（另含 `*_PUBLISH_EXCEPTION` 等发送失败） |
| `order_inventory_failure_total{result}` | 订单侧收口结果：`auto_cancelled` / `manual_required_paid` / `state_conflict` / `order_missing` |
| 告警 `OrderInventoryFailurePaid`（P1） | `increase(order_inventory_failure_total{result="manual_required_paid"}[10m]) > 0` → 人工介入 |

## 三、值班处置 SOP（收到 P1 告警）

1. **定位订单**：查 `order_inventory_failure_total` 对应日志 `[库存失败收口] 已付款订单库存失败 ... orderId/orderNo/skuId`；
2. **核对状态**：订单状态(1-已付款)、支付单状态、库存 Redis/MySQL 实际值；
3. **决策**：
   - 确无库存 → 走售后"仅退款"（v1 人工发起；v2 计划自动退款）；
   - 有库存（误报/镜像问题）→ 等对账拉平，观察下次对账结果；
4. **记录**：处置留痕（订单号、原因、动作、结果），复盘时归档到 `per-service-review` 台账。

## 四、已知局限（v1 → v2）

- [ ] 已付款订单**不自动退款**（v2：接售后/退款链路自动收口）；
- [ ] 发货守卫（履约前校验库存锁定态）尚未实现（v2 最后一道闸）；
- [ ] 映射缺失时事件被丢弃（依赖补录 Job ≤5 分钟 + 超时关单兜底）；
- [ ] 失败事件发布无重试（依赖指标 + runbook，而非消息可靠性）。

## 五、关联

- 讨论与决策背景：`docs/reports/distributed-semantics-grilling-20260927.md` §2.2（P0）；
- 台账：`docs/reports/per-service-review-20260921.md` §28；
- 专题：`docs/interview/xhs/03-库存一致性.md`、`40-RocketMQ深度拷打.md`。
