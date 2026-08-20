# G5-trade — 交易（重头）

> 服务：order(19011) + payment(19012) + inventory(19009) + coupon(19010) + product(19006) + user(19001) | 入口：**gateway(19000)**
> 依赖：G1 登录 + G3 商品（SKU/库存 init）+ G4 优惠券
> 时间引用：矩阵 **#3/4**（支付超时+关单）、**#5/6**（预扣/TCC 超时）、**#10-13**（xxl 订单任务）、**#14**（库存对账）、**#18/19/20**（支付补偿）、**#25**（下单幂等 24h）、**#29/30/31**（延时/重试/事务回查）、**#35**（登录锁）

## 回归记录（2026-08-15，Task9 后全量回归）

**G5-01（23/23 ✅）+ G5-02（18/18 ✅）= 41/41 全绿**。逐用例执行（禁止批量），测试用户 g5a_，数据已清理（订单分片/payment/refund/inventory/coupon 全 0，商品 SPU/SKU 0）。

### 回归要点（对照 Task8 记录 + 新发现）
- **全链路**：下单（事务消息+幂等 24h+锁）→ 支付（99 同步/1 模拟器 90%）→ 回调 → 退款（部分/全额）→ 关单/补偿全过
- **xxl 任务验证**：#6 支付超时（TIMEOUT/PAY_TIMEOUT 事件+DB/Redis 双写，T-062 修复后行为）、#7/#8 通知补偿、#9 退款超时、#10 关单、#11 补发、#12 死信重投、#13 映射修复、#14 库存对账（三场景）
- **P1-1 竞态自动退款实证**：payType=1 支付 → 订单先取消 → 模拟器回调 → 30009 → 自动退款（t_refund reason="订单已取消/状态不允许支付，自动退款"）
- **T-071 已修复确认**：refund-success 释放库存 + `restoreStockOnRefund` 退款回补（confirm 已清预扣记录后的独立语义）——total 92→94 实测
- **补偿消费者三动作实证**（Java 投递 ORDER_COMPENSATION_TOPIC）：RELEASE_STOCK 释放/ RETURN_COUPON 退券/ CLOSE_ORDER 关单 全过
- **T-110【观察·事件流缺陷】**：EVENT_CREATED 从不落库——EVENT_STATUS_MAP 中 CREATED→0 而创建时订单 status 已是 0 → appendEvent 幂等检查恒跳过（后续 CANCELLED seq=1 证明链断）。不影响功能，登记 ISSUES
- **T-068 已收敛**：SkuItem 嵌套校验生效（缺 skuId → 40002，非文档预期 50002/500）
- **文档差异记录**：inventory getStock 未初始化 → 30002"库存记录不存在"（非 initialized=false 结构，语义一致）；订单列表字段为 orderId 非 id；内部端点经 gateway 需 HMAC 签名（gateway 先拦）

### 关键实证
- 分库分表路由：db=uid%4, tb=uid/4%4 全链路（订单/明细/本地消息/事件/快照）
- pseudoOrderId=SHA-256(orderNo) 前 8 字节派生（有符号）预扣/释放一致
- TCC 三态 fence/detail 1→2→3 + 超时自动解冻
- 库存补偿单次回退不超发（T-064/065 修复后：total 98→100 恰 1 件）
- 双限流层（gateway 429 / 服务自身 40202）

## 业务范围
下单（事务消息 + 本地事务 + 库存预扣联动 + 优惠券核销 P0-A + 地址快照）→ 支付（Mock 30% 失败 / 独立支付服务 99/1/2 渠道 + 回调模拟器 90%）→ 退款（P1-1 竞态自动退款）→ 关单（延时消息 30min + xxl#10 兜底）→ 状态机 0→1→2→3 / 4 取消 / 5 退款

## 归属定时/联动任务
- **order 域（xxl 组 8）**：orderCloseJob(#10 每分钟兜底关单)、localMessageRetryJob(#11)、deadLetterScanJob(#12)、orderMappingRepairJob(#13)
- **payment 域（xxl 组 6）**：paymentTimeoutCheckJob(#6 每30s)、paymentNotifyCompensateJob(#7 每2min)、refundNotifyCompensateJob(#8 每3min)、refundTimeoutCheckJob(#9 每60s)
- **inventory 域**：inventoryReconcileJob(#14 xxl 每分钟，组 4)、PreDeductTimeoutJob(@Scheduled 60s)、TccTimeoutJob(@Scheduled 60s)、InventoryCompensationJob(@Scheduled 30s)、InventoryOutboxSenderJob(@Scheduled 5s)
- **进程内**：PayCallbackSimulator(@Scheduled 5s，回调闭环 90% 成功)、事务消息回查、延时关单消息（delayLevel=16=30min，配置可改 5=1min）

## 用例文档
- **G5-01-order.md**：下单/状态机/取消/支付回调联动/关单/补偿/本地消息/映射表（22 用例）
- **G5-02-inventory-payment.md**：库存三阶段/TCC/超时/对账/补偿 + 支付/回调/退款/超时/通知补偿（18 用例）

## 关键数据关注矩阵（代码实证 2026-08-14）
| 用例域 | Redis key | MySQL | MQ |
|---|---|---|---|
| 下单幂等 | `myxhs:order:idempotent:{bizIdentifier}`（24h）| t_order 分片(库=uid%4,表=uid/4%4) + t_order_item + t_local_message（同事务）| ORDER_TRANSACTION_TOPIC（事务消息）|
| 库存预扣 | `inventory:prededuct:{pseudoOrderId}`（Hash 30min）、`inventory:{skuId}:total/bucket:*`、`inventory:prededuct:index` | t_inventory（available/locked/freezing）| INVENTORY_TOPIC:PRE_DEDUCT/CONFIRM/RELEASE |
| 订单状态 | `myxhs:order:info:{orderId}` | t_order 状态机 0→1→2→3/4/5；t_order_event/t_order_snapshot | PAY_RESULT_TOPIC / REFUND_RESULT_TOPIC / ORDER_CLOSE_TOPIC(延时) / ORDER_COMPENSATION_TOPIC |
| 支付 | `myxhs:payment:paying/status/refunding:*`、`callback:pending:*`(5min) | t_payment/t_refund | PAY_RESULT_TOPIC、REFUND_RESULT_TOPIC |
| TCC | — | t_tcc_fence + t_tcc_freeze_detail（1/2/3）| — |
| 补偿 | `order:compensation:consumed:{msgId}`（10min）| t_local_message（0→1→死信3）| ORDER_COMPENSATION_TOPIC |
| 映射 | — | t_order_no_mapping（非分片键路由）| — |

## 执行纪律（G1-G4 教训）
- 服务重启必须带 INTERNAL_TOKEN/ADMIN_TOKEN（#79-1）；**order 支付回调类 Feign 依赖内部令牌**
- 限流/幂等窗口跨用例共享（#79-3）：create 5次/60s、cancel/deliver 10次/60s、支付 pay 10次/60s、refund 5次/60s
- **order 全部端点需 JWT+HMAC 签名**（/api/order/** 无 gateway 白名单）；内部回调端点（pay-success 等）X-Internal-Call
- **分库分表**：订单按 uid 路由（库=uid%4 表=(uid/4)%4）；SQL 构造订单须按分片插入 + t_order_no_mapping（主库）
- **写后读延迟**：事务消息异步落库（接口 200 时订单可能未落库）+ 主从——等 1-3s 再断言
- **订单查询**：必须 userId+orderId 联合（分片键路由）；跨库裸查须遍历 4 库 4 表
- 事务消息回查（#31）为单测级/审查级；延时关单（#29）用 xxl#10 手动触发或投 delayLevel=1 消息覆盖
- **测试前已修复（2026-08-14，见 ISSUES T-060~065）**：下单层 SPU 校验(T-060)、payment refund/status 鉴权(T-061)、支付超时 DB 扫描(T-062)、库存补偿链(T-063/064/065)——G5 用例按修复后行为断言
