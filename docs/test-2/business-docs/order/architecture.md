# my-xhs-order + my-xhs-payment 架构分析

## 一、服务拓扑

```
my-xhs-order (端口: 19005):
  -Xms512m -Xmx512m | SkyWalking → OAP 21.130.247.89:11800
  pay.type=mock (默认本地) / remote (独立支付服务)

my-xhs-payment (端口: 19009):
  -Xms256m -Xmx256m | SkyWalking → OAP 21.130.247.89:11800
  仅在 pay.type=remote 时加载
```

## 二、Controller 与安全

| 服务 | Controller | 端点数 | 认证 |
|------|------|:--:|------|
| order | OrderController (用户) | 9 | D01-D09: JWT + X-User-Id |
| order | OrderController (内部) | 5 | D10-D14: X-Internal-Call |
| payment | PaymentController | 5 | M01-M05: X-Internal-Call |

| 安全层 | 机制 | 适用 |
|------|------|------|
| 用户认证 | Gateway JWT → X-User-Id | D01-D09 |
| 内部调用 | X-Internal-Call(`myxhs.internal.token`) | D10-D14, M01-M05 |
| 并发(下单) | Redis SETNX幂等(24h) + Redisson锁 | D01 |
| 并发(状态) | Event Sourcing + 乐观锁 WHERE status=X | D05-D12 |
| 并发(支付) | Redisson锁(myxhs:lock:payment:pay:{orderId}) | M01/M03 |
| 消息幂等 | 事务消息回查 + 本地消息表dedup | ORDER_TRANSACTION_TOPIC |

## 三、Feign 调用汇总

| 调用方 | 目标服务 | 接口 | 用途 |
|------|------|------|------|
| order | product | GET /api/product/sku/batch | 批量查SKU信息 |
| order | inventory | GET /api/inventory/stock/{skuId} | 查库存 |
| order | inventory | POST /api/inventory/preDeduct | 库存预扣(pseudoOrderId) |
| order | inventory | POST /api/inventory/confirm | 库存确认 |
| order | inventory | POST /api/inventory/release | 库存释放 |
| order | coupon | GET /api/coupon/discount/{id} | 计算折扣(不核销) |
| order | coupon | POST /api/coupon/use | 用券核销 |
| order | coupon | POST /api/coupon/return | 退券 |
| order | payment | POST /api/payment/pay | 发起支付(remote) |
| order | payment | GET /api/payment/status/{orderId} | 支付状态 |
| payment | order | POST /api/order/pay-success | 支付成功回调 |
| payment | order | POST /api/order/pay-fail | 支付失败回调 |
| payment | order | POST /api/order/refund-success | 退款成功回调 |
| payment | order | POST /api/order/refund-fail | 退款失败回调 |

## 四、数据流

### 创建订单 (D01 — 全链路最复杂)
```
POST /api/order/create {items:[{skuId,quantity,couponId}]} + X-User-Id
  → OrderService.createOrder()
    → 1. SETNX myxhs:order:idempotent:{userId}:{requestId} TTL=24h (幂等)
    → 2. Redisson RLock myxhs:order:create:lock:{userId}
    → 3. Feign Product GET /api/product/sku/batch → {skuId→SkuInfo}
    → 4. Feign Inventory GET /api/inventory/stock/{skuId} → 前置库存校验
    → 5. Feign Coupon GET /api/coupon/discount/{id}?orderAmount=xxx (不核销, 仅算折扣)
    → 6. 计算: totalAmount - discountAmount = payAmount
    → 7. sendMessageInTransaction(ORDER_TRANSACTION_TOPIC, orderCreateMsg)
         → Listener.executeLocalTransaction:
             @Transactional: INSERT t_order + t_order_item + t_local_message(transaction_id=orderNo)
             → COMMIT / ROLLBACK
         → Broker超60s未确认 → checkLocalTransaction(查t_local_message)
    → 8. sendCloseDelayMessage(delayLevel=5=1min测试 / 16=30min生产)
    → 9. EventSourcing: INSERT t_order_event(ORDER_CREATED)
    → 10. INSERT t_order_snapshot (快照)
    → 11. INSERT t_order_no_mapping(orderNo→orderId→userId) (非分片键反查路由)
    → 返回 {orderId, orderNo, payAmount}
```

### 支付成功 (D10)
```
POST /api/order/pay-success {orderId} + X-Internal-Call (payment→order回调)
  → OrderService.onPaymentSuccess()
    → 映射表反查: SELECT user_id FROM t_order_no_mapping WHERE order_id=?
    → EventSourcing: INSERT t_order_event(ORDER_PAID)
    → 乐观锁: UPDATE t_order SET status=1,paid_at=NOW() WHERE status=0
    → Feign Inventory POST /api/inventory/confirm (pseudoOrderId, 转正式扣减)
    → Feign Coupon POST /api/coupon/use (核销优惠券)
    → 失败: sendCompensationMessage → ORDER_COMPENSATION_TOPIC
```

### 取消订单 (D05)
```
POST /api/order/cancel {orderId} + X-User-Id
  → OrderService.cancelOrder()
    → EventSourcing: INSERT t_order_event(ORDER_CANCELLED)
    → 乐观锁: UPDATE t_order SET status=4 WHERE status=0
    → CompletableFuture并行:
        Feign Inventory POST /api/inventory/release (释放预扣库存)
        Feign Coupon POST /api/coupon/return (退券)
    → 任一失败 → sendCompensationMessage → ORDER_COMPENSATION_TOPIC
```

### Mock支付 (D08)
```
POST /api/order/pay/create {orderId} + X-User-Id (pay.type=mock)
  → MockPayService.createPayment(orderId, payAmount)
    → INSERT my_xhs_payment.t_payment{orderId, amount, status=PENDING}
    → Thread.sleep(100ms) 模拟处理
    → Random: 90% → status=SUCCESS → onPaymentSuccess → ORDER_PAID
               10% → status=FAILED → onPaymentFailed → ORDER_CANCELLED
```

## 五、订单状态机

```
                    ┌─ 创建订单 ─┐
                    ↓             ↓
                0: 待支付 ←─ 延时关单(30min) → 4: 已取消
                    ↓ (支付成功)
                1: 已付款
                    ↓ (发货)
                2: 已发货
                    ↓ (确认收货)
                3: 已完成
                    ↓ (退款)
                5: 已退款
```

## 六、事务消息链路

```
createOrder():
  → sendMessageInTransaction(ORDER_TRANSACTION_TOPIC, orderCreateMsg)
    → Listener: 检查状态 → 本地事务: INSERT t_order + INSERT t_order_item
      → 成功: commit → 库存预扣 consumer 消费
      → 失败: rollback
      → 未知: Producer 回查 (checkLocalTransaction)
  → 发送延时消息: ORDER_CLOSE_TOPIC(delayLevel=5/16)

回查机制: Producer 实现 checkLocalTransaction(MessageExt msg)
  → SELECT status FROM t_order WHERE order_id=?
  → status=0 且未超时: UNKNOW(继续回查)
  → status!=0 或已关单: ROLLBACK
```

## 七、Event Sourcing

```
状态变更不直接 UPDATE status，而是:
  → INSERT t_order_event(event_type, order_id, before_status, after_status, version, data, request_id)
  → INSERT IGNORE request_id 去重
  → 乐观锁 UPDATE t_order SET status=? WHERE id=? AND status=?

7种事件类型:
  ORDER_CREATED / ORDER_PAID / ORDER_DELIVERED / ORDER_COMPLETED
  ORDER_CANCELLED / ORDER_REFUNDED / ORDER_CLOSED
```

## 八、分库分表详解

```
双层路由规则:
  库 = user_id % 4 → ds{0-3}
  表 = (user_id / 4) % 4 → t_order_{0-3}

绑定表组: t_order + t_order_item + t_local_message + t_order_snapshot + t_order_event
  → 同库同表，JOIN不跨库

全局表: t_order_no_mapping (每库全量)
  → orderNo 非分片键反查时，SELECT user_id FROM t_order_no_mapping WHERE order_no=?
```

## 九、支付双通道

```
pay.type=mock (默认):
  MockPayService.createPayment() → 90%成功 / 10%失败
  → 成功: 触发 onPaymentSuccess
  → 失败: 触发 onPaymentFailed → 自动取消订单

pay.type=remote:
  Order Feign → PaymentController(/api/payment/pay + X-Internal-Call)
  → 支付服务处理 → 回调 /api/order/pay-success 或 /api/order/pay-fail
  MQ 兜底: PAY_RESULT_TOPIC (Feign超时时MQ通知)
```

## 十、补偿机制

```
本地消息表(t_local_message):
  → sendMessageInTransaction 写入
  → Producer 发送 MQ 失败 → status=PENDING
  → CompensationJob 定时扫描 PENDING → 重发 ORDER_COMPENSATION_TOPIC

死信: ORDER_COMPENSATION_TOPIC 消费失败 N 次后 → t_dead_letter
对账: OrderReconcileJob 定时对账 t_order vs t_local_message
```

## 十一、Mock支付策略

```
MockPayService.createPayment():
  1. 生成支付单 → INSERT t_payment
  2. 模拟处理(线程sleep 100ms)
  3. 根据配置 mock.pay.success-rate (默认90%) 随机决定
     → 成功: UPDATE status=SUCCESS + notify order 服务
     → 失败: mark FAILED + notify order 服务取消订单释放库存
```

## 十二、Redis Key 完整清单

| Key | 类型 | TTL | 用途 |
|------|------|:--:|------|
| `myxhs:order:idempotent:{userId}:{requestId}` | String | 24h | D01幂等(SETNX) |
| `myxhs:order:create:lock:{userId}` | String | — | D01 Redisson锁 |
| `order:seq:{date}` | String | 1d | 订单号生成(Redis INCR) |
| `myxhs:order:info:{orderId}` | String | 30min | 订单缓存 |
| `order:compensation:consumed:{msgId}` | String | 24h | 补偿消息去重(SETNX) |
| `myxhs:payment:paying:{orderId}` | String | — | 支付进行中标记(幂等) |
| `myxhs:payment:status:{orderId}` | String | 1h | 支付状态缓存 |
| `myxhs:lock:payment:pay:{orderId}` | String | — | 支付Redisson锁 |
| `myxhs:lock:payment:refund:{paymentId}` | String | — | 退款Redisson锁 |
| `myxhs:payment:refunding:{paymentId}` | String | — | 退款进行中标记(幂等) |
| `myxhs:payment:notify:count:{orderId}` | String | — | 支付通知次数 |
| `myxhs:payment:callback:pending:{orderId}` | String | 5min | 回调pending标记 |
