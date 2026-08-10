# D03: 订单列表 — GET /api/order/list

## § 源码分析

- **Controller**: `OrderController.java:68` → `@GetMapping("/list")`, 参数 `X-User-Id` + optional `@RequestParam Integer status`
- **Service**: `OrderService.java:329` → `getUserOrders()`
  - 分库定位: user_id%4 决定库
  - `SELECT * FROM t_order WHERE user_id=? AND deleted=0` + (status不为空 `AND status=?`)
  - ORDER BY create_time DESC
- **下游**: 分库MySQL t_order

## § 业务逻辑

分库定位→按userId分页查询→可选状态过滤(0待付/1已付/2已发/3完成/4取消/5退款)→按时间倒序→返回List<OrderVO>

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/order/list` | 200, 数组 |
| HTTP | `curl /api/order/list?status=1` | 200, 仅已付款订单 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 分库 | 单库查询不跨库 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/order/list" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -20
```

## § ASCII流转图

```
curl GET /api/order/list?status=1
  → OrderController.getUserOrders(X-User-Id, status)
    → 分库定位 → SELECT * FROM t_order WHERE user_id=? AND status=? ORDER BY create_time DESC
    → 返回 List<OrderVO>
```
