# my-xhs-order + payment 业务逻辑分析

## 一、创建订单（全链路）

```
createOrder(userId, items[{skuId, quantity, couponId}])
  → 幂等: SETNX myxhs:order:create:{userId}:{requestId} 10秒锁
  → Feign product: 批量取SKU信息(batchGetSkuDetails)
  → Feign inventory: 获取实时库存(getStock)
  → Feign coupon: 计算优惠金额(getCouponDiscount) — 不核销
  → 计算金额: 运费 + 商品总额 - 优惠金额 = payAmount
  → 事务消息: sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)
    → 本地事务: INSERT t_order + t_order_item + INSERT t_local_message
    → 半消息成功 → 库存预扣 Consumer 消费
    → MQ 发送成功 → 回查机制兜底
  → 延时关单: SendDelayMsg(ORDER_CLOSE_TOPIC, delayLevel=5/16)
  → 返回 OrderVO
```

## 二、订单状态流转

```
支付成功(onPaymentSuccess):
  Event Sourcing: INSERT t_order_event(ORDER_PAID)
  → 乐观锁: UPDATE t_order SET status=1 WHERE status=0
  → 核销优惠券: CouponFeign.useCoupon()
  → 库存确认: InventoryFeign.confirmStock()

发货(deliverOrder):
  → 乐观锁: UPDATE t_order SET status=2 WHERE status=1
  → INSERT logistics TX

确认收货(confirmReceive):
  → 乐观锁: UPDATE t_order SET status=3 WHERE status=2

取消订单(cancelOrder):
  → 乐观锁: UPDATE t_order SET status=4 WHERE status=0
  → 库存释放: InventoryFeign.releaseStock()
  → 退券: CouponFeign.returnCoupon()

延时关单:
  → MessageListenerConcurrently 消费 ORDER_CLOSE_TOPIC
  → SELECT status FROM t_order WHERE id=? → status!=0 直接返回
  → status=0 → onPaymentFailed 流程(库存释放+退券)
```

## 三、库存联动

```
创建: pseudoOrderId预扣(preDeductStock)
  → Redis inventory 模块处理

支付成功: 确认(confirmStock, pseudoOrderId)
  → 转正式扣减

取消/关单: 释放(releaseStock, pseudoOrderId)
  → 回退预扣库存

幂等: pseudoOrderId 防止重复扣减
```

## 四、支付流程

```
Mock模式:
  POST /api/order/pay/create → MockPayService
  → 90%成功: [success] → onPaymentSuccess(order→已付, stock, coupon)
  → 10%失败: [fail] → onPaymentFailed(自动取消订单, release stock+coupon)

Remote模式:
  POST /api/order/pay/create → Feign PaymentFeignClient.pay()
  → POST /api/payment/pay → PaymentService.pay()
  → 支付处理 → 回调 Order /api/order/pay-success
  → 外层 catch → MQ PAY_RESULT_TOPIC 兜底通知
```

## 五、补偿与对账

```
本地消息表补偿:
  CompensationJob 定时扫描 t_local_message WHERE status=PENDING
  → 补发 ORDER_COMPENSATION_TOPIC

延时关单回查:
  ORDER_CLOSE_TOPIC 消费者 查订单状态
  → 已支付/已取消: 跳过
  → 待支付 超时: onPaymentFailed

对账:
  OrderReconcileJob 扫描订单号连续性与状态完整性
```
