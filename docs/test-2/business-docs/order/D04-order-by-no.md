# D04: 订单号反查 — GET /api/order/by-order-no/{orderNo}

## § 源码分析

- **Controller**: `OrderController.java:77` → `@GetMapping("/by-order-no/{orderNo}")`, 参数 `X-User-Id` + `@PathVariable String orderNo`
- **Service**: `OrderService` → `getOrderByOrderNo()`
  - orderNo 非分片键 → 走映射表 t_order_no_mapping
  - `SELECT user_id FROM t_order_no_mapping WHERE order_no=?`
  - 获取 userId → 分库定位 → SELECT t_order + t_order_item
- **下游**: 全局表 t_order_no_mapping + 分库 t_order

## § 业务逻辑

订单号反查映射表获取userId → 分库路由 → SELECT订单+明细 → 返回OrderVO

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/order/by-order-no/{orderNo}` | 200, 订单信息 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 分库 | 全局映射表→分库路由 | ✅ |

## § curl

```bash
ORDER_NO="MX202608090001"
curl -s "http://localhost:19000/api/order/by-order-no/$ORDER_NO" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl GET /api/order/by-order-no/{orderNo}
  → OrderController.getOrderByOrderNo(orderNo, X-User-Id)
    → SELECT user_id FROM t_order_no_mapping WHERE order_no=?
    → 分库定位: ds{userId%4}.t_order_{(userId/4)%4}
    → SELECT t_order + JOIN t_order_item
    → 返回 OrderVO
```
