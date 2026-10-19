---
iteration: ITER-008
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 库存模块生产化改造

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | 逻辑事件已处理过 | 补发消息（新 msgId，同 order/sku/action） | 幂等拦截（不执行库存变更） | 是 |
| TC-2 | AC-1 | 事件未处理过 | 首次消息 | 正常执行 | 是 |
| TC-3 | AC-1 | outbox 行已存在（dup 场景） | markSent/cancel | 按 (order,sku,action) 命中（非 0 行） | 是 |
| TC-4 | AC-2 | L1 处理中途失败 | 重投 | 已扣 SKU 跳过（Lua -1）+ 未扣 SKU 补扣 | 是 |
| TC-5 | AC-2 | L1 成功 | 处理完成 | 完成后置标记（InOrder） | 是 |
| TC-6 | AC-2 | L2 同一事件重复投递 | 第二次投递 | tx 幂等跳过库存变更（不双扣） | 是 |
| TC-7 | AC-2 | L2 处理中途崩溃（模拟：无 tx 提交） | 重投 | 重新执行且只应用一次 | 是 |
| TC-8 | AC-2 | 终止消费者处理失败 | 重投 | 不置标记 → 重新释放（幂等） | 是 |
| TC-9 | AC-3 | DLQ 消息（attempt=0） | 重放 | 重投源 topic（deduct 保留 action tag）+ attempt=1 | 是 |
| TC-10 | AC-3 | DLQ 消息（attempt=3） | 重放 | 仅告警（over_limit）+ 不重投 | 是 |
| TC-11 | AC-3 | 重投失败 | 重放 | 抛异常不吞 + 指标 fail | 是 |
| TC-12 | AC-4 | — | 各脚本 KEYS 构造 | 全部同 tag（{skuId} 家族） | 是 |
| TC-13 | AC-4 | — | initStock/预扣/释放/确认/墓碑/退款回补 | 单键语义矩阵全绿（语义锁定） | 是 |
| TC-14 | AC-4 | 退款回补重复/崩溃重试 | refundRestore | delta=0 幂等（原子脚本，不双补不漏补） | 是 |
| TC-15 | AC-4 | 2-SKU 订单 | confirm 仅 1 个 SKU 记录在 | 写确认标记（F12）+ 迟到记录不被释放 | 是 |
| TC-16 | AC-4 | items 契约 | confirm/release 请求带 items | 按 items 驱动逐 SKU（order 侧 Map 含 items） | 是 |
| TC-17 | AC-4 | 超时 Job | per-sku 索引过期项 | 逐 (sku,order) 释放；标记存在→补确认 | 是 |
| TC-18 | AC-5 | 对账快照 vs 并发变更 | reconcile | CAS 0 行不动（下轮收敛） | 是 |
| TC-19 | AC-6 | — | cron/时间/文案注释 | 与部署基准一致（零残留） | 手工 |
| TC-20 | AC-7 | — | 指标/锁前缀 | recordInventoryAction 覆盖；前缀统一 | 手工 |
| TC-21 | AC-8 | 全仓 | 八模块全量重编测试 | 全绿（coupon 24 基线起） | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-22 | AC-1 | 原消息未投递（纯失败） | 补发正常执行（at-least-once 保持） | 是 |
| TC-23 | AC-2 | L2 条件失败（0 行） | 事件标记完成 + 失败事件（不重投） | 是 |
| TC-24 | AC-4 | items 缺失（异常调用） | 保守方向（confirm 写标记不释放；release 告警） | 是 |
| TC-25 | AC-4 | 旧键残留 | 新逻辑不受影响（清理说明） | 手工 |
| TC-26 | AC-4 | 墓碑命中（订单已终结） | 迟到预扣跳过（-3 语义不回归） | 是 |
| TC-27 | AC-4 | 扩容/热点机制移除 | 无 pause/resize 残留；预扣不因 pause 抛错 | 是 |
