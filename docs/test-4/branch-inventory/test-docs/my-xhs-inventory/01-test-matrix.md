# my-xhs-inventory 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| INV-L1-01 | 初始化库存 | POST /api/inventory/init {skuId,totalStock} | MySQL 初始化 100/0 | ✅ |
| INV-L1-02 | 重复初始化幂等 | 同 sku 二次 init | 40002已初始化拒绝 | ✅ |
| INV-L1-03 | 预扣库存 | preDeduct | available-locked，Redis 桶+Outbox | ✅ |
| INV-L1-04 | 确认扣减 | confirmDeduct | locked→0，Outbox CONFIRM | ✅ |
| INV-L1-05 | 释放库存 | releaseStock | available+，清预扣 | ✅ |
| INV-L1-06 | 退款回补 | refundRestore | available+ | ✅ |
| INV-L1-07 | 超卖防护 | 超量 preDeduct qty=999 | 30004库存不足 | ✅ |
| INV-L1-08 | 查库存 | GET /api/inventory/stock/{skuId} | available/locked/freezing | ✅ |
| INV-L1-09 | 扩容/reinit | POST /api/inventory/reinit | 200 重建 | ✅ |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| INV-L2-01 | Redis 分桶 total/bucket | Redis inventory key | ✅ |
| INV-L2-02 | 预扣记录 Hash/ZSet | prededuct idem | ✅ |
| INV-L2-03 | Outbox 表 | PRE_DEDUCT/CONFIRM 独立行 | ✅ |
| INV-L2-04 | t_inventory 状态 | available/locked/freezing | ✅ |
| INV-L2-05 | t_tcc_fence/freeze_detail | ✅ t_tcc_fence/freeze_detail表结构正确 |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| INV-L3-01 | 预扣超时恢复 | PreDeductTimeoutJob 释放 | ✅ |
| INV-L3-02 | MQ 失败回滚 | 预扣消息失败 Redis/Outbox 一致 | ⬜ |
| INV-L3-03 | TCC Try/Confirm/Cancel 幂等+fence | ✅ 11场景全过(try/confirm/cancel幂等、空回滚、悬挂拒绝、超量拒绝) |
| INV-L3-04 | 对账修复 | MySQL158/Redis148 → 对账修复148 | ✅ data:1 |
| INV-L3-05 | 并发预扣 | 20并发qty=1 | ✅ Redis/MySQL一致不超卖 |
| INV-L3-06 | 扩容窗口保护 | resize 时 confirm/release 延迟 | ⬜ |
| INV-L3-07 | 多SKU部分成功 | 2SKU:1成功1库存不足 | ✅ 失败触发重试,已成功SKU幂等不重复 |
| INV-L3-08 | 超量并发预扣 | 5×qty=50超库存 | ✅ 只扣100不超卖 |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| INV-L4-01 | Outbox 积压/失败指标 | ✅ inventory_action_total等指标暴露 |
| INV-L4-02 | 预扣超时/对账日志 | ✅ |
| INV-L4-03 | TraceId 跨 Order/Inventory | ✅ |

## 已实测（运行态复核）
- INV-L1-03/04/05/06/08、L2-01/03/04、L3-01 ✅
- 超卖防护/重复初始化/预扣/确认/释放/回补/reinit 已实测
- TCC/扩容窗口/对账修复 待专项(并发预扣/超量已实测)
