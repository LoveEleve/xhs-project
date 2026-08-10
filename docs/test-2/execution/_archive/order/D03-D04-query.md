# D03/D04 — 订单查询

## D03: GET /api/order/list — 订单列表

### ASCII 流转图
```
[curl] → Gateway:19000 → order:19011
  → OrderController.listOrders(X-User-Id=10001, ?pageNum=1&pageSize=3)
  → OrderService.listOrders()
     └ MySQL:13308 my_xhs_order_{0..3}.t_order_0
        SELECT * WHERE user_id=10001 AND deleted=0 ORDER BY id DESC LIMIT 3
```

### 业务逻辑
分页查询当前用户全部订单（4 分库联合查询），按创建时间倒序。返回订单状态、金额、创建时间等。

### curl
```bash
curl -s "http://localhost:19000/api/order/list?pageNum=1&pageSize=3" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

### 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, list format（非 PageResult） | ✅ |
| MySQL | 4 分库 t_order_0 联合查询 | ✅ |
| SkyWalking | traceId 贯穿 Gateway→order | ✅ |

---

## D04: GET /api/order/by-order-no/{orderNo} — 按订单号查

### ASCII 流转图
```
[curl] → Gateway:19000 → order:19011
  → OrderController.getByOrderNo(/by-order-no/ORD20260807...)
  → OrderService.getByOrderNo()
     └ MySQL:13308 my_xhs_order_{0..3}.t_order_0
        SELECT * WHERE order_no = ? AND user_id = ?
```

### curl
```bash
curl -s "http://localhost:19000/api/order/by-order-no/ORD2026080713175213900010014" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

### 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, orderId+status+amount 正确 | ✅ |
| MySQL | t_order_0 单行查询 | ✅ |
| SkyWalking | traceId 贯穿 | ✅ |
