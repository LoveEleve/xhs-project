# D07: 发货 — POST /api/order/deliver

## § 源码分析

- **Controller**: `OrderController.java:107` → `@PostMapping("/deliver")`, 参数 `X-User-Id` + `@Valid @RequestBody DeliverRequest{orderId, logisticsCompany, trackingNo}`
- **Service**: `OrderService.java:520` → `deliverOrder()`
  - EventSourcing: INSERT t_order_event(ORDER_DELIVERED)
  - 乐观锁: `UPDATE t_order SET status=2, logistics_company=?, tracking_no=? WHERE id=? AND status=1`
- **下游**: 分库MySQL t_order + t_order_event

## § 业务逻辑

EventSourcing→乐观锁(status:1已付→2已发)→写物流→返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 订单状态=1(已付) | `SELECT status FROM t_order WHERE id=?` | 乐观锁失败 |
| RateLimit | 10次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/order/deliver -d '{...}'` | 200 |
| MySQL | `SELECT status, logistics_company FROM t_order WHERE id=?` | 2,"顺丰速运" |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | 乐观锁 WHERE status=1 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/order/deliver \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orderId":'"$ORDER_ID"',"logisticsCompany":"顺丰速运","trackingNo":"SF1234567890"}'
```

## § ASCII流转图

```
curl POST /api/order/deliver + {orderId, logisticsCompany, trackingNo}
  → INSERT t_order_event(ORDER_DELIVERED)
  → UPDATE t_order SET status=2, logistics=? WHERE id=? AND status=1
```
