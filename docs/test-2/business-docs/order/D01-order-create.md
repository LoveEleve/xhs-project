# D01: 创建订单 — POST /api/order/create

## § 源码分析

- **Controller**: `OrderController.java:48` → `@PostMapping("/create")`, 参数 `X-User-Id` + `@Valid @RequestBody OrderCreateRequest`
- **Service**: `OrderService.java:108` → `createOrder()`
  - 幂等锁: `SETNX myxhs:order:create:{userId}:{requestId}` 10秒
  - Feign product: batchGetSkuDetails(skuIds) 获取商品信息
  - Feign inventory: getStock(skuIds) 实时库存
  - Feign coupon: getCouponDiscount(couponId, orderAmount) 计算优惠
  - 计算 payAmount = totalAmount - couponDiscount + freight
  - 事务消息: sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)
    → 本地事务: INSERT t_order + INSERT t_order_item + INSERT t_local_message
  - 延时关单: SendDelayMsg(ORDER_CLOSE_TOPIC, delayLevel)
  - 库存预扣: InventoryFeign.preDeductStock(pseudoOrderId)
- **下游**: 分库MySQL + MQ(事务/延时) + Feign 5个(product×1/inventory×2/coupon×2)

## § 业务逻辑

幂等SETNX → Feign取SKU/库存/优惠 → 计算实付金额 → 事务消息可靠写入 → 延时关单防超时 → 库存预扣 → 返回OrderVO

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 有购物车选中商品 | 请求体 items 不为空 | 创建失败 |
| 幂等锁未持有 | `r.ttl('myxhs:order:create:{userId}:{requestId}')` < 0 | 重复提交返回原订单 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/order/create -d '{...}'` | 200, 含orderId/payAmount |
| MySQL | `SELECT * FROM t_order_0 WHERE user_id=?` | 1行 (分库) |
| MQ | RocketMQ Dashboard ORDER_TRANSACTION_TOPIC | 有消息 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | 事务消息+回查 | ✅ |
| 幂等 | SETNX 10秒锁 | ✅ |
| 超时 | 延时关单30min | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "items":[{"skuId":2085989641275572226,"quantity":1}],
    "addressId":1,
    "couponId":null,
    "remark":"测试订单"
  }' | python3 -m json.tool
```

## § ASCII流转图

```
curl POST /api/order/create + body{items, addressId, couponId}
  → Gateway → OrderController.createOrder(X-User-Id, request)
    → SETNX 幂等锁(userId+requestId, 10s)
    → Feign Product: batchGetSkuDetails + 库存 getStock
    → Feign Coupon: getCouponDiscount(不核销)
    → 计算 payAmount = total - discount + freight
    → sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)
      → 本地事务: INSERT t_order + t_order_item + t_local_message
      → Producer→Listener→commit→库存预扣
    → SendDelayMsg(ORDER_CLOSE_TOPIC, delayLevel)
    → 返回 OrderVO{orderId, payAmount, status=0}
```
