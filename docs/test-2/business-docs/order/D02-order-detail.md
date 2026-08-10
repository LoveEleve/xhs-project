# D02: 订单详情 — GET /api/order/{orderId}

## § 源码分析

- **Controller**: `OrderController.java:59` → `@GetMapping("/{orderId}")`, 参数 `X-User-Id` + `@PathVariable Long orderId`
- **Service**: `OrderService.java:287` → `getOrderDetail()`
  - 分库分表路由: user_id%4→库, (user_id/4)%4→表
  - `SELECT * FROM t_order WHERE id=? AND user_id=? AND deleted=0`
  - LEFT JOIN t_order_item WHERE order_id=?
- **下游**: 分库MySQL t_order + t_order_item

## § 业务逻辑

分库Router定位 → SELECT订单主表+明细(JOIN同库同表) → 反序列化快照信息 → 返回OrderVO

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 订单不存在 | orderId 有效 | 返回null/错误 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/order/{orderId}` | 200, 含status/payAmount/items |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 分库 | 绑定表组JOIN不跨库 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/order/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl GET /api/order/{orderId}
  → Gateway → OrderController.getOrderDetail(X-User-Id, orderId)
    → 分库: ds{userId%4}.t_order_{(userId/4)%4}
    → SELECT * FROM t_order WHERE id=? AND user_id=? AND deleted=0
    → JOIN t_order_item ON order_id=?
    → 返回 OrderVO{id, orderNo, status, payAmount, items}
```
