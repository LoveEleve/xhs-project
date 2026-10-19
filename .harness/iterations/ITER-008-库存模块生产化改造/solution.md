---
iteration: ITER-008
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md 中的 REQ/AC；本文件 confirmed 前不得进入 coding
---

# 技术方案: 库存模块生产化改造

> 机器可读的迭代协议产物之一。把 prd.md 的 REQ 转化为**实现任务与观测点**。
> 纪律：具体方法逻辑以代码为准，本文件只说明「调哪个接口/方法 + 它在整条链路中的位置」。

## 方案概述

```
目标键模型（单键 + 每脚本单 slot）：
  inventory:{skuId}:stock                   单库存键（替代 total+bucket:N+bucket:count）
  inventory:prededuct:{skuId}:{orderId}     预扣记录（String=数量，EX=到期+缓冲）
  inventory:terminated:{skuId}:{orderId}    终结墓碑（同 tag）
  inventory:prededuct:index:{skuId}         过期索引（ZSet member=orderId；空则自动消亡）
  inventory:refund:{skuId}:{orderId}        退款累计键（同 tag，与 stock 同脚本原子）
  inventory:confirmed:{orderId}             确认标记（per-order，Java 单键读）
  （删除：bucket:* / bucket:count / paused / resize:lock / 全局 prededuct:index）

幂等语义（目标）：
  L1 预扣入口：完成态标记（处理后置；崩溃→重投→Lua 逐 SKU 幂等补扣）
  L2 落账：去前置标记 → t_inventory_l2_event INSERT IGNORE 与库存变更同事务；
          版本门改"完成后推进"（崩溃→重投→tx 幂等不丢不重）
  退款回补：累计推进+库存 INCRBY 同槽单脚本（崩溃→重试 delta=0 幂等）
  终止：完成态标记（释放本身幂等：墓碑+记录不存在即返回）
```

四阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 可靠性 | F1/F2 逻辑事件幂等 + F4（L1/L2/终止） + F8 DLQ 重放 | 双扣族/崩溃窗口/DLQ 三清零 |
| ② 键模型 | F5 + F11 + F12 + F4-C（退款原子） + items 契约 | 集群正确 + 单键最简 |
| ③ 工程 | F6 CAS + F7 注释 + F9 指标 + F10 锁前缀 | 与改造水准对齐 |
| ④ 收口 | 台账回写 + 修订记录 + 对照文档 + 八模块回归 | 闭环 |

## REQ → 任务映射

### 阶段① 可靠性（REQ-1/2/3 / AC-1/2/3）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-1 | T1.1 L2 去前置 msgId 标记（由 T1.4 事务内幂等承接——逻辑事件键 = inbox PK）；幂等矩阵 = 补发拦截（inbox 命中）/未处理放行 | `consumer/InventoryDeductConsumer.java` | 单测：补发拦截/未处理放行 |
| AC-1 | T1.2 `markOutboxSent`/`cancelOutboxEvent` 改 `WHERE order_id=? AND sku_id=? AND action=?`（app 侧调用点 + Job 侧同步） | `mapper/InventoryMapper.java`、`service/InventoryService.java`、`job/InventoryOutboxSenderJob.java` | 单测：dup 场景按 key 生效 |
| AC-2 | T1.3 L1 完成态标记：`OrderTransactionConsumer` 标记移到循环完成后；失败不置标记（去掉失败 removeMark 的必要性，保留防御） | `consumer/OrderTransactionConsumer.java` | 单测：失败不置/成功后置/重投补扣 |
| AC-2 | T1.4 L2 事务内幂等：新增 `t_inventory_l2_event`（PK order_id,sku_id,action）+ 消费端"INSERT IGNORE + 库存变更"同事务 + 版本门"完成后推进"（去掉 reserve/restore 前置占位）；7 天清理并入对账 | `consumer/InventoryDeductConsumer.java`、`mapper/InventoryMapper.java`、`job/InventoryReconcileJob.java`、`sql/init-all.sql` | 单测：重复投递不双扣/崩溃重投不丢 |
| AC-2 | T1.5 终止消费者完成态标记（释放幂等：墓碑+无记录即返回，崩溃重投安全） | `consumer/OrderTerminationConsumer.java` | 单测：失败不置/成功后置 |
| AC-3 | T1.6 DLQ 重放消费者 ×3 组（transaction/deduct/termination）：重投源 topic（deduct 保留 action tag）、attempt 属性 +1、≤3、超限告警 | `consumer/InventoryDlqReplayConsumer.java`（新） | 单测：重投/超限/失败不吞 |

### 阶段② 键模型（REQ-4 / AC-4）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-4 | T2.1 Lua 重写（单键家族）：`prededuct.lua`（KEYS: stock/record/tombstone/index 全 {skuId}；ARGV: skuId/orderId/qty/expire/ttl）、`release.lua`（stock/record/index）、`confirm.lua`（record/index）、`mark_terminated.lua`（record/tombstone）；新增 `refund_restore.lua`（refundKey/stock 原子：delta+推进+INCRBY）；删 `reconcile_buckets.lua` | `resources/lua/*`、`config/RedisScriptConfig.java` | 单测：键构造断言同 tag |
| AC-4 | T2.2 Service 去桶重构：删除 `resizeBuckets`/`ResizeInProgressException`/异步线程池+@PreDestroy/`hotSkuDetector`+计数器/桶常量与 `bucketKey()`/`bucketCountKey()`/`hot/defaultBucketCount` 配置注入；简化 `initStock/doPreDeduct/preDeduct/confirmDeduct/releaseStock/refundRestore/rebuildStockFromDb/reinitStock/getStock` 共 9 方法；`rollbackPreDeduct` 简化 | `service/InventoryService.java` | 单测：单键语义矩阵 |
| AC-4 | T2.3 超时 Job：per-sku 索引 SCAN → 逐 (sku,order) `releasePreDeduct`/`settleAsConfirmedIfMarked`（补确认按单 SKU） | `job/PreDeductTimeoutJob.java` | 单测：SCAN 释放/补确认 |
| AC-4 | T2.4 items 契约：`ConfirmDeductRequest`/`ReleaseStockRequest` + `items[{skuId,quantity}]`；order `doConfirmInventoryDeduct`/`doReleaseInventory` Map 加 items（orderItemMapper 取）；inventory `OrderTerminationConsumer` 解析 payload items | `dto/request/*`、order `OrderService.java`、`consumer/OrderTerminationConsumer.java` | 单测：items 驱动确认/释放 |
| AC-4 | T2.5 F12 标记条件：confirm 后"任一预期 SKU 缺失即写标记" | `service/InventoryService.java` | 单测：部分缺失写标记 |
| AC-4 | T2.6 收尾：`InventoryCacheEvictConsumer` DELETE 键清单改单键；`InventoryCompensationJob` 去桶号读取；`InventoryReconcileJob` 删 `reconcileBuckets()`；DTO 删 bucketCount（`InventoryInitRequest`/`ReinitRequest`/`StockVO`）；Controller 简化；yml 删 bucket 配置；**product 侧** `InitRequest`/StockVO 字段删 | 多文件 | 编译 + 单测 |
| AC-4 | T2.7 测试重写：3 个受影响测试文件（43 处机械改写）+ 单键语义锁定新增 | `src/test/**` | 全绿 |

### 阶段③ 工程（REQ-5/6/7 / AC-5/6/7）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-5 | T3.1 CAS：`updateAvailableStockOnly`/`updateLockedStockOnly` 加 `AND available_stock=#{snapshot}`/`AND locked_stock=#{snapshot}`（调用方传快照旧值；0 行=并发变更，下轮收敛） | `mapper/InventoryMapper.java`、`job/InventoryReconcileJob.java` | 单测：CAS 参数 |
| AC-6 | T3.2 注释：ReconcileJob 类级+方法级 cron 对齐（`0 0 * * * ?`，基准 init-xxljob.sql:112）；TimeoutJob "每 5 分钟"→1 分钟；Sender "券"文案；两消费者 javadoc（幂等语义更新） | `job/*`、`consumer/*` | grep 零残留 |
| AC-7 | T3.3 指标：`recordInventoryAction` 覆盖 confirm/release/refund 成功失败、outbox 补发/取消、补偿重试、对账修复、DLQ 重放 | `service/InventoryService.java`、`job/*`、`consumer/*` | 代码核验 |
| AC-7 | T3.4 锁前缀：`lock:job:inventory:*` → `myxhs:lock:job:inventory:*`（3 处） | `job/InventoryOutboxSenderJob.java` 等 | grep |

### 阶段④ 收口（REQ-8 / AC-8）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-8 | T4.1 八模块全量回归 + 全仓编译 | 全仓 | 测试报告 |
| AC-8 | T4.2 台账回写：问题 1（评估后移除+键模型重构）/2（CAS）/3（完成态+tx 幂等）→"已修复"；问题 4/5 补标"已修复（提交证据）" | `docs/学习/工程问题台账.md` | 文档 |
| AC-8 | T4.3 库存对照文档（模块对照系列补项，六段格式 + 裁决） | `docs/学习/模块对照/13-库存.md`（新） | 文档 |
| AC-8 | T4.4 讲解系列修订记录（分桶移除对 04/05/12 等篇的影响） | `docs/学习/库存-改造/02-改造后文章修订记录.md`（新） | 文档 |

## 接口与数据变更

- **REST**：`/confirm` `/release` 载荷增 `items`；`StockVO`/`InitRequest`/`ReinitRequest` 删 `bucketCount`（product 侧同步）
- **消息**：新 DLQ 重放消费者 ×3；无新业务 topic
- **数据模型**：`t_inventory_l2_event`（新表，PK (order_id,sku_id,action)，7 天清理）
- **Redis**：键模型收敛（见方案概述）；旧键清理写入部署说明

## 关键设计决策

| 决策 | 结论 | 理由 / 权衡 |
|------|------|------------|
| 去桶 | 单 key | 当前形态零收益（total 热 key + 同 slot）；真形态属演进方向 |
| 幂等键 | `{orderId}:{skuId}:{action}` | uk 保证唯一；稳定跨 msgId 重发；无需 id 传递技法 |
| items 契约 | order 侧事实源随请求走 | 拒绝订单→SKU 索引（跨 slot 无法原子，重开 F12 类窗口） |
| L2 机制 | tx 幂等表 + 版本门完成后推进 | 崩溃安全；接受罕见跨事件并发竞态（条件+对账兜底） |
| 退款原子 | 累计键改 {skuId} tag + 单脚本 | 崩溃→重试 delta=0 幂等；顺带 cluster-correct |
| F12 | 任一缺失即写标记 | 部分缺失=迟到风险；补确认不释放（安全方向） |
| 记录不做 | 真分片/TCC/CDC | 克制 |

## 风险与降级

| 风险 | 影响 | 缓解 / 降级 |
|------|------|------------|
| 键模型重构回归 | 预扣/释放/确认链路 | 测试重写 + 语义锁定矩阵 + 深审查逐项 |
| items 契约改动面 | order/inventory 双侧 | 单部署同发；缺失防御（保守方向） |
| L2 并发跨事件竞态 | DB 镜像乱序 | 条件更新 + 失败事件 + 对账收敛 |
| 旧键残留 | 内存占用 | 部署说明一次性清理 |
| 补发长期失败 | 券…事件延迟 | outbox-stuck 告警 + DLQ 重放 + 对账 |
