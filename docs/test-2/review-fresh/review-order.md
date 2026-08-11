# Order 模块 Review

## 分布式 / 消息（重点）
1. **[高] 本地消息表补发 = 每单必然重复投递（P0-8 确认，且比预想更糟）**
   - 下单用 RocketMQ **事务消息**（sendMessageInTransaction）→ 本地事务写 t_local_message(status=0) → COMMIT。
   - 但 t_local_message 状态**从不被事务消息路径置为成功**（markSuccess 仅由 LocalMessageRetryJob 自己调用）。
   - LocalMessageRetryJob 每 30s 扫 status=0/2 且 nextRetryTime<=now → **每一单的本地消息都会补发一次**
     （初始 nextRetryTime=now，OrderTransactionService.java:103）→ 每单 ORDER_TRANSACTION 投递 2 次。
   - 下游库存预扣按 pseudoOrderId 幂等，故不超卖，但**每单重复消费**是确定的资源浪费与隐患。
   - 建议：事务消息 Commit 后联动 markSuccess；或二选一（只留事务消息，本地表仅作 broker 故障兜底）；
     或本地表作为唯一通道（去掉事务消息）。

2. **[高] 补偿消费者忽略 action，统一走 closeTimeoutOrder → 已支付订单库存泄漏（P0-1/P0-10）**
   OrderCompensationConsumer.java:113-116 注释明示"待修"，当前 RELEASE_STOCK/RETURN_COUPON
   一律调 closeTimeoutOrder。closeTimeoutOrder 只处理 status=0；若订单已支付(status=1)但释放库存失败
   发补偿 → 补偿被跳过 → **库存永久泄漏**。需按 action 分发到 releaseInventory/returnCouponIfUsed，
   且这些方法需对"非待付款"订单仍执行库存/券释放。

3. **[高] 已取消订单可支付、竞态无退款（P0-2 确认）**
   onPaymentSuccess 捕获乐观锁 IllegalStateException（取消/关单先赢）后仅 return false
   （OrderService.java:642-647），**不发退款**。钱已扣但订单已取消 → 用户付了钱没货也没退款。
   需在支付成功但订单非待付款时触发退款（幂等退款）。

4. **[中] pseudoOrderId = fold-hash(orderNo) 作库存幂等键**（OrderService.java:468-474, 447）
   用 orderNo 字符串折半哈希当 orderId，跨单存在碰撞可能 → 幂等键串号 → 释放错单库存。
   低概率但属脏设计；建议以真实 orderId 落库后再释放，或引入更可靠幂等键。

5. **[中] generateOrderNo 在 Redis 故障时降级随机序列号**（OrderService.java:814-817）
   Redis 不可用时随机数可能撞号 → 重复 orderNo → 映射表/幂等键冲突。边缘风险。

## 状态机 / 幂等
6. EventSourcing appendEvent + 乐观锁 updateStatusWithLock + INSERT IGNORE 唯一索引 —— 设计扎实。
7. **[低] OrderCompensationConsumer Redis 去重非原子**（hasKey→set 两段）
   并发消费可能双处理（依赖下游幂等兜底）。建议 SETNX。

## 工程 / 线程
8. **[中] cancelOrder/onPaymentFailed/closeTimeoutOrder/confirmInventoryDeduct 用 CompletableFuture.runAsync 默认 commonPool**
   裸公共线程池：无 MDC(traceId) 传播 + 可能阻塞公共池。对应 O2 类问题。
9. 地址快照取 user 服务，失败静默为空 JSON —— 可接受降级，但无告警。

## 分库分表
10. 非分片键查询靠 t_order_no_mapping 反查 + userId 路由 —— 正确。映射写入失败仅记日志，依赖补录 job。
