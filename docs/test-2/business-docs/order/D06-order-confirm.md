# D06: 确认收货 — POST /api/order/confirm

## § 源码分析

- **Controller**: `OrderController.java:97` → `@PostMapping("/confirm")`, 参数 `X-User-Id` + `@RequestParam Long orderId`
- **Service**: `OrderService.java:489` → `confirmReceive()`
  - EventSourcing: INSERT t_order_event(ORDER_COMPLETED)
  - 乐观锁: `UPDATE t_order SET status=3, completed_at=NOW() WHERE id=? AND status=2`
- **下游**: 分库MySQL t_order + t_order_event

## § 业务逻辑

用户收到货→点击确认→EventSourcing记录事件→乐观锁UPDATE(status:2已发货→3已完成)→返回ok

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录+订单归属 | userId匹配 | 403 |
| 订单状态=2(已发货) | `SELECT status FROM t_order WHERE id=?` | 乐观锁失败 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/order/confirm?orderId={id}` | 200 |
| MySQL | `SELECT status FROM t_order WHERE id=?` | 3(COMPLETED) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | 乐观锁WHERE status=2 | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/order/confirm?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl POST /api/order/confirm?orderId={id}
  → OrderController.confirmReceive(X-User-Id, orderId)
    → INSERT t_order_event(ORDER_COMPLETED)
    → UPDATE t_order SET status=3, completed_at=NOW() WHERE id=? AND status=2
    → 乐观锁检查 affected>0
```
