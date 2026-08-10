# my-xhs-order + my-xhs-payment 订单与支付服务

> 19端点(order 14 + payment 5) | 分库分表 ShardingSphere-JDBC | 事务消息+EventSourcing+双通道支付

---

## 架构概览

```
用户端 HTTP(X-User-Id)  → OrderController(/api/order)  → OrderService → 分库分表 MySQL
                             ↓ Feign                         ↓ MQ
                          PaymentFeignClient ←→ PaymentController(/api/payment)
                             ↓ (remote模式)                  ↓
                          PaymentService → MySQL t_payment
```

## 端点分类 (19个)

### Order 端点 (14个)

| ID | 方法 | 路径 | 类型 | 说明 | 认证 |
|------|------|------|------|------|:--:|
| D01 | POST | `/api/order/create` | 用户 | 创建订单(全链路最复杂) | JWT+X-User-Id |
| D02 | GET | `/api/order/{orderId}` | 用户 | 订单详情 | JWT+X-User-Id |
| D03 | GET | `/api/order/list` | 用户 | 我的订单列表 | JWT+X-User-Id |
| D04 | GET | `/api/order/by-order-no/{orderNo}` | 用户 | 订单号反查(映射表路由) | JWT+X-User-Id |
| D05 | POST | `/api/order/cancel` | 用户 | 取消订单(EventSourcing) | JWT+X-User-Id |
| D06 | POST | `/api/order/confirm` | 用户 | 确认收货 | JWT+X-User-Id |
| D07 | POST | `/api/order/deliver` | 用户 | 发货(状态流转) | JWT+X-User-Id |
| D08 | POST | `/api/order/pay/create` | 用户 | 创建支付(mock/remote) | JWT+X-User-Id |
| D09 | GET | `/api/order/pay/status/{orderId}` | 用户 | 支付状态查询 | JWT+X-User-Id |
| D10 | POST | `/api/order/pay-success` | 内部 | 支付成功回调 | X-Internal-Call |
| D11 | POST | `/api/order/pay-fail` | 内部 | 支付失败回调 | X-Internal-Call |
| D12 | POST | `/api/order/refund-success` | 内部 | 退款成功回调 | X-Internal-Call |
| D13 | POST | `/api/order/refund-fail` | 内部 | 退款失败回调 | X-Internal-Call |
| D14 | GET | `/api/order/pay-amount` | 内部 | 订单应付金额(支付校验) | X-Internal-Call |

### Payment 端点 (5个, X-Internal-Call鉴权)

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| M01 | POST | `/api/payment/pay` | 发起支付 | X-Internal-Call |
| M02 | POST | `/api/payment/callback/{payType}` | 第三方支付回调 | X-Internal-Call |
| M03 | POST | `/api/payment/refund` | 发起退款 | X-Internal-Call |
| M04 | POST | `/api/payment/refund-callback/{payType}` | 第三方退款回调 | X-Internal-Call |
| M05 | GET | `/api/payment/status/{orderId}` | 查询支付状态 | X-Internal-Call |

## 数据库 (分库分表)

```
双层路由: user_id % 4 → 库 (ds0, ds1, ds2, ds3)
         (user_id / 4) % 4 → 表 (t_order_0..3)

6层表:
  t_order_{n}             订单主表 (user_id分片键)
  t_order_item_{n}        订单商品明细表 (绑定表组)
  t_local_message_{n}     本地消息表(事务消息)
  t_order_snapshot_{n}    订单快照表
  t_order_event_{n}       订单事件表(EventSourcing)
  t_order_no_mapping      订单号→分片键映射表(不分片，全局表)
```

## Feign 依赖

```
Order Service 调用:
  ProductFeignClient   → my-xhs-product   (sku信息+库存)
  InventoryFeignClient → my-xhs-product   (库存预扣/确认/释放)
  CouponFeignClient    → my-xhs-coupon    (折扣/用券/退券 x3)

Payment Feign (remote模式):
  PaymentFeignClient   → my-xhs-payment   (支付/状态查询 x3)
```

## MQ 拓扑

| Topic | 用途 |
|------|------|
| ORDER_TRANSACTION_TOPIC | 事务消息(创建订单→库存预扣) |
| ORDER_CLOSE_TOPIC | 延时关单(delayLevel=5=1min测试/16=30min 生产) |
| ORDER_COMPENSATION_TOPIC | 补偿消息(本地消息表补发) |
| PAY_RESULT_TOPIC | 支付结果通知 |
| REFUND_RESULT_TOPIC | 退款结果通知 |
